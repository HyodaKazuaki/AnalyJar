import entity.Jar
import entity.JarClass
import entity.JarDependency
import entity.JarMethod
import fr.dutra.tools.maven.deptree.core.InputType
import fr.dutra.tools.maven.deptree.core.Node
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import org.apache.maven.model.Dependency
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.FileReader
import java.io.IOException
import java.lang.ProcessBuilder.Redirect.INHERIT
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.jar.JarFile
import kotlin.io.path.*
import kotlin.system.exitProcess

fun findNodeFromFileName(nodes: List<Node>, fileName: String): Node? {
    return nodes.find { "${it.artifactId}-${it.version}.jar" == fileName } ?: run {
        val childNodes = nodes.map {
            println("${it.artifactId} -> ${it.childNodes}")
            it.childNodes
        }.flatten()
        println(childNodes)
        if (childNodes.isEmpty()) return null
        return findNodeFromFileName(childNodes, fileName)
    }
}

fun getArtifactInformation(nodes: List<Node>, fileName: String): Dependency? {
    val node = findNodeFromFileName(nodes, fileName)
    if (node != null) {
        val dependency = Dependency()
        dependency.groupId = node.groupId
        dependency.artifactId = node.artifactId
        dependency.version = node.version
        return dependency
    }
    return null
}

fun insertDependencies(nodes: List<Node>) {
    transaction {
        addLogger(StdOutSqlLogger)
        nodes.forEach { node ->
            val dependentArtifactId =
                Jar.select { (Jar.groupId eq node.groupId) and (Jar.artifactId eq node.artifactId) and (Jar.version eq node.version) }
                    .single()[Jar.id]
            node.childNodes.forEach { dependentNode ->
                val dependedArtifactId =
                    Jar.select {
                        (Jar.groupId eq dependentNode.groupId) and
                                (Jar.artifactId eq dependentNode.artifactId) and
                                (Jar.version eq dependentNode.version)
                    }.single()[Jar.id]
                JarDependency.insert {
                    it[dependId] = dependentArtifactId
                    it[dependedId] = dependedArtifactId
                }
            }
        }
    }

    nodes.forEach { insertDependencies(it.childNodes) }
}

fun main(args: Array<String>) {
    val argParser = ArgParser("AnalyJar")
    val pomPath by argParser.argument(ArgType.String, description = "Pom file path")
    val dbHost by argParser.option(ArgType.String, description = "Database host address").default("localhost")
    val dbPort by argParser.option(ArgType.Int, description = "Database port number").default(3306)
    val dbName by argParser.option(ArgType.String, description = "Database name").default("parse")
    val dbUsername by argParser.option(ArgType.String, description = "Database user name").required()
    val dbPassword by argParser.option(ArgType.String, description = "Database user password").required()
    argParser.parse(args)

    Database.connect(
        "jdbc:mysql://$dbHost:$dbPort/$dbName",
        driver = "com.mysql.cj.jdbc.Driver",
        user = dbUsername,
        password = dbPassword
    )
    transaction {
        addLogger(StdOutSqlLogger)
        if (Jar.exists().not()) {
            SchemaUtils.create(Jar)
        }
        if (JarClass.exists().not()) {
            SchemaUtils.create(JarClass)
        }
        if (JarMethod.exists().not()) {
            SchemaUtils.create(JarMethod)
        }
        if (JarDependency.exists().not()) {
            SchemaUtils.create(JarDependency)
        }
    }

    val outputDir = createTempDirectory(prefix = "dependencies_jar")
    val treePath = createTempFile(prefix = "dependencies", suffix = "txt")
    val mavenXpp3Reader = MavenXpp3Reader()
    val pomModel = mavenXpp3Reader.read(FileReader(pomPath))
    println("Download jar files to $outputDir...")
    outputDir.createDirectories()
    val processBuilder = ProcessBuilder()
    try {
        val copyDependenciesCommand =
            "mvn dependency:copy-dependencies -DoutputDirectory=${outputDir.absolutePathString()} -f $pomPath"
        val exitValueCopyDependencies = processBuilder.command(
            copyDependenciesCommand.split(
                " "
            )
        )
            .redirectOutput(INHERIT)
            .redirectError(INHERIT)
            .start()
            .waitFor()
        if (exitValueCopyDependencies != 0) throw IOException("Failed to execute command $copyDependenciesCommand")
        println("Generate dependencies tree")
        val treeCommand =
            "mvn dependency:tree -DoutputFile=${treePath.absolutePathString()} -DoutputType=text -f $pomPath"
        val exitValueTree = processBuilder.command(
            treeCommand.split(
                " "
            )
        )
            .redirectOutput(INHERIT)
            .redirectError(INHERIT)
            .start()
            .waitFor()
        if (exitValueTree != 0) throw IOException("Failed to execute command $treeCommand")
    } catch (e: IOException) {
        println("Error happened\n${e.message}")
        exitProcess(1)
    }

    val inputType = InputType.TEXT
    val dependentTreeParser = inputType.newParser()
    val tree = dependentTreeParser.parse(treePath.inputStream().reader().buffered()) ?: run {
        println("Failed to parse $treePath")
        exitProcess(1)
    }

    val jars = outputDir.listDirectoryEntries("*.jar")
    val urlClassLoader = URLClassLoader.newInstance(jars.map { it.toUri().toURL() }.toList().toTypedArray())
    jars.forEach { file ->
        transaction {
            addLogger(StdOutSqlLogger)

            val dependenceJar =
                pomModel.dependencies.find { "${it.artifactId}-${it.version}.jar" == file.fileName.toString() }
                    ?: run {
                        println("========== FIND DEPENDENCY TREE =============")
                        getArtifactInformation(tree.childNodes, file.fileName.toString()) ?: run {
                            println("Dependence jar file ${file.fileName} information not found.")
                            exitProcess(1)
                        }
                    }
            val jarId = Jar.insert { jar ->
                jar[groupId] =
                    dependenceJar.groupId
                jar[artifactId] = dependenceJar.artifactId
                jar[fileName] = file.fileName.toString()
                jar[version] = dependenceJar.version
            } get Jar.id
            val jar = JarFile(file.toFile())
            val url = file.toUri().toURL()
            println("===== $url =====")

            jar.entries().asSequence().filter { it.isDirectory.not() && it.name.endsWith(".class") }
                .forEach { entry ->
                    val className = entry.name.substring(0, entry.name.length - 6).replace('/', '.')
                    try {
                        val clazz = urlClassLoader.loadClass(className)
                        println(clazz.name)
                        val classId = JarClass.insert { jarClass ->
                            jarClass[name] = clazz.name
                            jarClass[this.jarId] = jarId
                        } get JarClass.id
                        clazz.methods.forEach {
                            println("Method: ${it.name}")
                            JarMethod.insert { jarMethod ->
                                jarMethod[name] = it.name
                                jarMethod[modifiers] = Modifier.toString(it.modifiers)
                                jarMethod[this.classId] = classId
                                jarMethod[returnTypeName] = it.returnType.name
                                jarMethod[parameters] =
                                    it.parameterTypes.map { parameter -> "${parameter.typeName} ${parameter.name}" }
                                        .joinToString(", ")
                            }
                        }
                    } catch (e: Error) {
                        System.err.println("${e.javaClass.name} : ${e.message}")
                    } catch (e: Exception) {
                        System.err.println("${e.javaClass.name} : ${e.message}")
                    }
                }
        }
    }

    insertDependencies(tree.childNodes)
}