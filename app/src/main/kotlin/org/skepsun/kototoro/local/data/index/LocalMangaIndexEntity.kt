package org.skepsun.kototoro.local.data.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import org.skepsun.kototoro.core.db.entity.MangaEntity

@Entity(
	tableName = "local_index",
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
class LocalContentIndexEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "path") val path: String,
	/**
	 * 文件最后修改时间戳（毫秒）。持久化后，本地列表可以只做 stat 检查，
	 * 避免每次进入应用都重新读取并解析所有本地文件。
	 */
	@ColumnInfo(name = "mtime", defaultValue = "0") val mtime: Long = 0L,
)
