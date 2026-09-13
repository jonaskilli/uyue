package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.CharacterProfile

@Dao
interface CharacterProfileDao {

    @Query(
        """select * from characterProfiles where bookUrl = :bookUrl
        order by mentionCount desc, updatedAt desc"""
    )
    fun getByBook(bookUrl: String): List<CharacterProfile>

    @Query("select * from characterProfiles where bookUrl = :bookUrl and name = :name")
    fun get(bookUrl: String, name: String): CharacterProfile?

    @Query("select name from characterProfiles where bookUrl = :bookUrl")
    fun getNames(bookUrl: String): List<String>

    @Query("select count(*) from characterProfiles where bookUrl = :bookUrl")
    fun countByBook(bookUrl: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg profile: CharacterProfile)

    @Update
    fun update(vararg profile: CharacterProfile)

    @Query("delete from characterProfiles where bookUrl = :bookUrl and name = :name")
    fun delete(bookUrl: String, name: String)

    @Query("delete from characterProfiles where bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)
}
