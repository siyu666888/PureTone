package com.puretone.player.playback

import com.puretone.player.data.local.entity.SongEntity

/**
 * 服务尚未绑定（player 为 null）时暂存的「设置队列并播放」请求。
 *
 * 由 [PlaybackCommandGate] 持有，`PlaybackManager.bind()` 时重放。
 */
internal data class PendingPlay(
    val songs: List<SongEntity>,
    val startIndex: Int,
    val startPositionMs: Long
)

/**
 * [PlaybackCommandGate.drain] 的结果：一次性取走的全部挂起命令。
 *
 * 三者顺序即 bind 时的重放顺序：显式点歌 > 追加 > 续播。
 */
internal data class PendingCommands(
    val play: PendingPlay?,
    val enqueue: List<SongEntity>,
    val restore: Boolean
) {
    val isEmpty: Boolean get() = play == null && enqueue.isEmpty() && !restore
}

/**
 * 队列命令闸门：把「过期命令作废」的隐式约定收敛成一处显式状态机。
 *
 * ## 为什么需要它
 *
 * 拆分前这三份状态散在 `PlaybackManager` 的 20 余处读写点里，彼此靠注释维持不变量，
 * 已实际产生过下列缺陷（全部为「索引/代次错位」类）：
 * - `removeFromQueue` / `enqueue` / `playNext` 改变了队列索引映射，却**没有复位**
 *   `apeRetryIndex`（它按队列内索引记忆）→ 删掉前一首后，另一个 APE 的下标被错认成
 *   「已重试过」，出错时跳过转码直接卡在 IDLE；
 * - `clearQueue` 完全没有递增代次 → 清空后，仍挂起在 APE 转码里的旧 `setQueue` 恢复
 *   时会把队列**又写回来**（表现为「清空了却自己冒出来」）；
 * - `pendingPlay` 与 `pendingRestore` 的互斥只能靠每个写入点自己记得清，漏一处就会
 *   「bind 后旧会话覆盖用户新点的歌」。
 *
 * ## 三类状态的语义
 *
 * 1. **代次（generation）**：单调递增。异步任务在挂起点（APE 转码可达数十秒）之后
 *    用 [isCurrent] 自检，过期即放弃写入——否则旧队列会覆盖新队列。
 * 2. **挂起命令槽**：player 未就绪时的暂存区。[pendPlay] 内部强制清掉 restore，
 *    保证「显式点歌优先于自动续播」这条规则不依赖调用方记性。
 * 3. **APE 重试标记**：按队列内索引记，防止「出错 → 转码 → 再出错」死循环。
 *    因按索引记忆，任何队列结构变化都必须复位——这就是 [onQueueStructureChanged]
 *    必须把两个动作绑在一起的原因。
 *
 * ## 线程
 *
 * 所有调用点都在主线程（`PlaybackManager.scope` 为 `Dispatchers.Main.immediate`）。
 * [apeRetryIndex] 另加 `@Volatile`：它会被 `handlePlayerError` 回调读取，而该回调由
 * ExoPlayer 内部线程派发，稳妥起见不依赖主线程可见性。
 */
internal class PlaybackCommandGate {

    /** 队列命令代次（P1-3）。每次「设置队列」取号，异步任务恢复后自检。 */
    private var generation: Int = 0

    private var pendingPlay: PendingPlay? = null
    private var pendingEnqueue: List<SongEntity> = emptyList()
    private var pendingRestore: Boolean = false

    /**
     * 已重试过转码的队列索引（-1 表示无）。
     * 按索引记忆，故随队列结构变化整批作废，见 [onQueueStructureChanged]。
     */
    @Volatile
    private var apeRetryIndex: Int = -1

    // ---------- 代次 ----------

    /**
     * 为一次「设置队列」命令取号。
     *
     * 必须在**第一个挂起点之前**调用：取号位置若晚于挂起，两个并发命令会拿到同一个
     * 代次，双双通过 [isCurrent] 检查。
     */
    fun nextGeneration(): Int = ++generation

    /** 命令是否仍是最新的（挂起点之后自检用） */
    fun isCurrent(generation: Int): Boolean = generation == this.generation

    /**
     * 队列结构发生变化（增删改序、清空、停止）：让在途命令失效，并作废 APE 重试标记。
     *
     * 两个动作刻意合并成一个入口——它们的触发条件完全相同（「队列内索引映射变了」），
     * 历史上正是因为分两处写、调用方漏了一半才出的 bug。
     */
    fun onQueueStructureChanged() {
        generation++
        apeRetryIndex = -1
    }

    /**
     * 单独复位 APE 重试标记（不递增代次）。
     *
     * 供「新队列即将生效」的路径使用：此刻代次刚被自己 [nextGeneration] 刷新，
     * 若调 [onQueueStructureChanged] 会再递增一次，把自己取的号弄失效。
     */
    fun resetApeRetry() {
        apeRetryIndex = -1
    }

    // ---------- 挂起命令槽 ----------

    /**
     * 暂存「设置队列并播放」。
     *
     * 内部清掉 [pendingRestore]：用户显式点歌永远优先于自动续播。若两条命令在 bind 后
     * 并发执行，恢复出的旧队列会覆盖用户新点的歌（代次守卫救不了——恢复命令最后执行、
     * 代次最新，先执行的点歌反而被判过期丢弃）。
     */
    fun pendPlay(req: PendingPlay) {
        pendingPlay = req
        pendingRestore = false
    }

    /** 暂存「追加到队列末尾」，多次调用累加（保持用户点击顺序） */
    fun pendEnqueue(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        pendingEnqueue = pendingEnqueue + songs
    }

    /**
     * 暂存「续播上次会话」。
     *
     * 不清 [pendingPlay]：续播是「兜底」，若已有显式点歌在排队，[drain] 后调用方会先
     * 处理点歌；而 [pendPlay] 的互斥清理保证两者不会同时生效。
     */
    fun pendRestore() {
        pendingRestore = true
    }

    /**
     * 取走并清空全部挂起命令（bind 时一次性重放）。
     *
     * 一次性原子取走而非逐个 peek + 清：避免重放过程中新命令插入导致顺序错乱，
     * 例如重放到一半用户又点了歌。
     */
    fun drain(): PendingCommands {
        val snapshot = PendingCommands(pendingPlay, pendingEnqueue, pendingRestore)
        pendingPlay = null
        pendingEnqueue = emptyList()
        pendingRestore = false
        return snapshot
    }

    /**
     * 作废「追加」与「续播」两类挂起命令（服务解绑时）。
     *
     * 刻意**保留** [pendingPlay]：它是用户显式点的歌，服务被重建后仍应执行。
     * 另两类依赖当前会话的队列与设置，解绑后语义已失效。
     */
    fun clearSessionBoundPending() {
        pendingEnqueue = emptyList()
        pendingRestore = false
    }

    /** 作废全部挂起命令（显式停止时——用户意图是「什么都别放」，包括已排队的点歌） */
    fun clearPending() {
        pendingPlay = null
        pendingEnqueue = emptyList()
        pendingRestore = false
    }

    // ---------- APE 重试标记 ----------

    /**
     * 尝试取得 [index] 处 APE 的重试资格。
     *
     * @return true 表示首次重试，调用方应执行转码；false 表示该索引已重试过，
     *         再次出错应放弃（否则「转码失败 → 再出错 → 再转码」无限循环）。
     */
    fun tryAcquireApeRetry(index: Int): Boolean {
        if (apeRetryIndex == index) return false
        apeRetryIndex = index
        return true
    }
}
