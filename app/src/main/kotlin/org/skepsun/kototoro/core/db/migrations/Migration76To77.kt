package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地内容索引表新增 mtime 列，用于持久化文件修改时间，
 * 使本地列表能够跳过未变化文件的重复解析（进入应用不再全量重扫）。
 */
class Migration76To77 : Migration(76, 77) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE `local_index` ADD COLUMN `mtime` INTEGER NOT NULL DEFAULT 0")
	}
}
