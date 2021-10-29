package entity

import org.jetbrains.exposed.sql.Table

object Jar : Table("jars") {
    val id = integer("id").autoIncrement()
    override val primaryKey = PrimaryKey(id, name = "pk_jar_id")
    val groupId = text("group_id")
    val artifactId = text("artifact_id")
    val fileName = text("file_name")
    val version = text("version")
}
