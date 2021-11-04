package entity

import org.jetbrains.exposed.sql.Table

object JarClass : Table("jar_classes") {
    val id = integer("id").autoIncrement()
    override val primaryKey = PrimaryKey(id, name = "pk_jar_class_id")
    val package_name = text("package")
    val name = text("name")
    val modifiers = text("modifiers")
    val jarId = (integer("jar_id") references Jar.id)
}
