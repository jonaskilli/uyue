package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.AiProfileTemplate

@Dao
interface AiProfileTemplateDao {

    @Query("SELECT * FROM aiProfileTemplate ORDER BY isDefault DESC, sortOrder ASC, lastUpdateTime ASC")
    fun getAllList(): List<AiProfileTemplate>

    @Query("SELECT * FROM aiProfileTemplate WHERE id = :id")
    fun getById(id: Long): AiProfileTemplate?

    @Query("SELECT * FROM aiProfileTemplate WHERE isDefault = 1 LIMIT 1")
    fun getDefault(): AiProfileTemplate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg template: AiProfileTemplate)

    @Update
    fun update(vararg template: AiProfileTemplate)

    @Delete
    fun delete(vararg template: AiProfileTemplate)

    @Query("DELETE FROM aiProfileTemplate WHERE id = :id")
    fun deleteById(id: Long)
}
