package entity

import org.jetbrains.exposed.sql.Table

object JarMethod : Table("operations") {
    val id = integer("id").autoIncrement()
    override val primaryKey = PrimaryKey(id, name = "pk_operation_id")
    val modifiers = text("modifiers")
    val name = text("name")
    val returnTypeName = text("return_type_name")
    val parameters = text("parameters")
    val classId = (integer("class_id") references JarClass.id)
}