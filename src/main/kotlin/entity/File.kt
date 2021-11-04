package entity

import org.jetbrains.exposed.sql.Table

object File : Table("files") {
    val id = integer("id").autoIncrement()
    override val primaryKey = PrimaryKey(id, name = "pk_file_id")
    val name = text("name")
    val path = text("path")
}