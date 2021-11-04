package entity

import org.jetbrains.exposed.sql.Table

object JarClass : Table("classes") {
    val id = integer("id").autoIncrement()
    override val primaryKey = PrimaryKey(id, name = "pk_class_id")
    val package_name = text("package")
    val name = text("name")
    val modifiers = text("modifiers")
    val fileId = (integer("file_id") references File.id)
}
