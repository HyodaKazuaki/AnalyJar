package entity

import org.jetbrains.exposed.sql.Table

object JarDependency : Table("jar_dependencies") {
    val id = integer("id").autoIncrement()
    override val primaryKey = PrimaryKey(id, name = "pk_jar_dependency_id")
    val dependId = (integer("depend_id") references Jar.id)
    val dependedId = (integer("depended_id") references Jar.id)
}