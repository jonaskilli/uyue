package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.AiChapterFixTemplate

@Dao
interface AiChapterFixTemplateDao {

    @Query("SELECT * FROM aiChapterFixTemplate ORDER BY isDefault DESC, sortOrder ASC, lastUpdateTime ASC")
    fun getAllList(): List<AiChapterFixTemplate>

    @Query("SELECT * FROM aiChapterFixTemplate WHERE id = :id")
    fun getById(id: Long): AiChapterFixTemplate?

    @Query("SELECT * FROM aiChapterFixTemplate WHERE isDefault = 1 LIMIT 1")
    fun getDefault(): AiChapterFixTemplate?

    @Query("SELECT MIN(sortOrder) FROM aiChapterFixTemplate WHERE isDefault = 0")
    fun getMinSortOrderOfNonDefault(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg template: AiChapterFixTemplate)

    @Update
    fun update(vararg template: AiChapterFixTemplate)

    @Delete
    fun delete(vararg template: AiChapterFixTemplate)

    @Query("DELETE FROM aiChapterFixTemplate WHERE id = :id")
    fun deleteById(id: Long)
}
