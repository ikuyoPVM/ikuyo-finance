package xyz.ikuyo.finance.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val emoji: String = "🏷️",
    val colorArgb: Int = 0xFF6750A4.toInt(),
    /** 默认分类不得因升级或导入而意外丢失 */
    val isDefault: Boolean = true,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val colorArgb: Int = 0xFF6750A4.toInt(),
)

@Entity(
    tableName = "bill_tags",
    primaryKeys = ["billId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["id"],
            childColumns = ["billId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("billId"), Index("tagId")],
)
data class BillTagCrossRef(
    val billId: Long,
    val tagId: Long,
)