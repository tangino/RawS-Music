package com.rawsmusic.module.data.prefs

import android.content.Context
import androidx.room.withTransaction
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.entity.PlaylistGroupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 分组操作结果。文案映射留给 UI，数据层不产出用户可见字符串。 */
enum class PlaylistGroupOpResult {
    Success,

    /** 名字为空或全是空白。 */
    BlankName,

    /** 用了保留名「默认」。该分组由列默认值兜底、必须永远存在，不能被创建/改名/删除。 */
    ReservedName,

    /** 同名分组已存在（name 是主键，直接写会抛 SQLiteConstraintException）。 */
    DuplicateName,

    /** 目标分组不存在。 */
    NotFound,
}

/**
 * 歌单分组。
 *
 * ## 与「默认」分组的关系（容易踩坑，先读这段）
 *
 * 「默认」**不存进 `playlist_groups` 表**：它由 `user_playlists.group_name` 的列默认值兜底
 * （见 `PlaylistGroupEntity` 的类注释）。好处是永远不可能出现「无归属歌单」；
 * 代价是读分组列表时必须**自己把它补在最前面**，本仓库用 [refresh] 统一处理，
 * 调用方不要再去表里找它。
 *
 * 因此「默认」是保留分组：不能创建、不能改名、不能删除。
 *
 * ## 为什么改分组必须走事务
 *
 * 「分组改名」与「删除分组」都要同时改两张表：`playlist_groups` 自身 + `user_playlists.group_name`。
 * 只成功一半就会让歌单指向一个**不存在的分组**（悬空），界面上那批歌单会直接消失。
 * 所以这里统一用 `withTransaction` 包起来。
 */
object PlaylistGroupStore {

    private val _groups = MutableStateFlow<List<PlaylistGroupEntity>>(emptyList())
    /** 分组列表，「默认」永远排第一（即使表里没有它）。 */
    val groups: StateFlow<List<PlaylistGroupEntity>> = _groups.asStateFlow()

    private val _playlistGroups = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 歌单 id → 分组名。用于按分组过滤歌单。 */
    val playlistGroups: StateFlow<Map<String, String>> = _playlistGroups.asStateFlow()

    private const val DEFAULT_GROUP = PlaylistGroupEntity.DEFAULT_GROUP_NAME

    private fun database(context: Context) = MusicDatabase.getInstance(context.applicationContext)

    private val SEED_EMPTY_GROUPS = setOf(
        "🚗 车载专用", "📱 手机精选", "长途自驾", "睡前轻音乐", "QQ 音乐"
    )

    /** 重新查询分组与歌单归属并发布。所有写操作之后都必须调它。 */
    suspend fun refresh(context: Context) {
        val db = database(context)
        val storedRaw = withContext(Dispatchers.IO) { db.playlistGroupDao().getAllBySortOrder() }
        val playlists = withContext(Dispatchers.IO) { db.userPlaylistDao().getPlaylists() }

        // 自动清理没有任何歌单引用的预设种子空分组，避免云端恢复带来的无用空分组长期残留
        val usedGroupNames = playlists.map { it.groupName }.toSet()
        val emptySeedGroups = storedRaw.filter { it.name in SEED_EMPTY_GROUPS && it.name !in usedGroupNames }
        val stored = if (emptySeedGroups.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                emptySeedGroups.forEach { db.playlistGroupDao().delete(it.name) }
                db.playlistGroupDao().getAllBySortOrder()
            }
        } else {
            storedRaw
        }

        _groups.value = buildList {
            // 「默认」永远在最前，且 order 固定为 -1，避免用户排序后它被挤到中间。
            add(PlaylistGroupEntity(DEFAULT_GROUP, sortOrder = -1))
            // 表里若误存了「默认」就跳过它（不改用户数据），避免界面上出现两个「默认」。
            stored.filter { it.name != DEFAULT_GROUP }
                .sortedBy { it.sortOrder }
                .forEach { add(it) }
        }
        _playlistGroups.value = playlists.associate { it.playlistId to it.groupName }
    }

    /** 已存在的分组名集合（含「默认」）。UI 用来提前拦截重名。 */
    fun existingNames(): Set<String> = _groups.value.mapTo(mutableSetOf()) { it.name }

    /**
     * 新建分组。
     *
     * @return [PlaylistGroupOpResult.Success] 表示新建成功（之后应重新 [refresh]）。
     */
    suspend fun create(context: Context, name: String): PlaylistGroupOpResult {
        val trimmed = name.trim()
        validateName(trimmed)?.let { return it }
        if (trimmed in existingNames()) return PlaylistGroupOpResult.DuplicateName

        val db = database(context)
        withContext(Dispatchers.IO) {
            val current = db.playlistGroupDao().getAllBySortOrder()
            val nextOrder = (current.maxOfOrNull { it.sortOrder } ?: -1) + 1
            db.playlistGroupDao().upsert(PlaylistGroupEntity(trimmed, nextOrder))
        }
        refresh(context)
        return PlaylistGroupOpResult.Success
    }

    /**
     * 分组改名，**同时**把其下歌单的归属改到新名（同一事务）。
     */
    suspend fun rename(context: Context, oldName: String, newName: String): PlaylistGroupOpResult {
        if (oldName == DEFAULT_GROUP) return PlaylistGroupOpResult.ReservedName
        val trimmed = newName.trim()
        validateName(trimmed)?.let { return it }
        if (trimmed == oldName) return PlaylistGroupOpResult.Success
        if (trimmed in existingNames()) return PlaylistGroupOpResult.DuplicateName

        val db = database(context)
        val now = System.currentTimeMillis()
        return try {
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    val dao = db.playlistGroupDao()
                    // 分组不存在就抛异常把事务回滚，绝不留下「改了一半」的状态。
                    if (dao.getAllBySortOrder().none { it.name == oldName }) {
                        throw NoSuchElementException(oldName)
                    }
                    dao.rename(oldName, trimmed)
                    db.userPlaylistDao().reassignGroup(oldName, trimmed, now)
                }
            }
            refresh(context)
            PlaylistGroupOpResult.Success
        } catch (missing: NoSuchElementException) {
            // 只接住「分组不存在」这一种；CancellationException 等其它异常自然向上抛。
            refresh(context)
            PlaylistGroupOpResult.NotFound
        }
    }

    /**
     * 删除分组，其下歌单**回落「默认」**（同一事务）。
     */
    suspend fun delete(context: Context, name: String): PlaylistGroupOpResult {
        if (name == DEFAULT_GROUP) return PlaylistGroupOpResult.ReservedName
        val db = database(context)
        val now = System.currentTimeMillis()
        val existed = withContext(Dispatchers.IO) {
            db.withTransaction {
                val dao = db.playlistGroupDao()
                if (dao.getAllBySortOrder().none { it.name == name }) {
                    false
                } else {
                    dao.delete(name)
                    // 顺序很重要：先删分组再改归属，中间不会出现「歌单指向已删分组」的可见状态
                    // （事务内外部读不到中间态），但语义上先删更符合直觉。
                    db.userPlaylistDao().reassignGroup(name, DEFAULT_GROUP, now)
                    true
                }
            }
        }
        refresh(context)
        return if (existed) PlaylistGroupOpResult.Success else PlaylistGroupOpResult.NotFound
    }

    /** 把单个歌单移到指定分组。 */
    suspend fun movePlaylist(context: Context, playlistId: String, groupName: String): PlaylistGroupOpResult {
        if (groupName !in existingNames()) return PlaylistGroupOpResult.NotFound
        val db = database(context)
        withContext(Dispatchers.IO) {
            db.userPlaylistDao().updatePlaylistGroup(playlistId, groupName, System.currentTimeMillis())
        }
        refresh(context)
        return PlaylistGroupOpResult.Success
    }

    /**
     * 按给定顺序重排分组（只影响 `sort_order`）。
     *
     * 传入的名字里若含「默认」会被忽略 —— 它的顺序由 [refresh] 固定在最前。
     */
    suspend fun reorder(context: Context, orderedNames: List<String>) {
        val db = database(context)
        withContext(Dispatchers.IO) {
            val dao = db.playlistGroupDao()
            orderedNames.filter { it != DEFAULT_GROUP }.forEachIndexed { index, name ->
                dao.updateSortOrder(name, index)
            }
        }
        refresh(context)
    }

    private fun validateName(trimmed: String): PlaylistGroupOpResult? = when {
        trimmed.isBlank() -> PlaylistGroupOpResult.BlankName
        trimmed == DEFAULT_GROUP -> PlaylistGroupOpResult.ReservedName
        else -> null
    }
}
