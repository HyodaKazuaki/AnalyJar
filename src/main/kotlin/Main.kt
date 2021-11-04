import entity.*
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

class Main {
    companion object : Logging {
        private val logger = logger()

        @JvmStatic
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
                addLogger(Slf4jSqlDebugLogger)
                if (Jar.exists().not()) {
                    SchemaUtils.create(Jar)
                    logger.info("Created jars table")
                }
                if (JarClass.exists().not()) {
                    SchemaUtils.create(JarClass)
                    logger.info("Created jar_classes table")
                }
                if (JarMethod.exists().not()) {
                    SchemaUtils.create(JarMethod)
                    logger.info("Created jar_methods table")
                }
                if (JarDependency.exists().not()) {
                    SchemaUtils.create(JarDependency)
                    logger.info("Created jar_dependencies table")
                }
            }

            val outputDir = createTempDirectory(prefix = "dependencies_jar")
            val treePath = createTempFile(prefix = "dependencies", suffix = "txt")
            val mavenXpp3Reader = MavenXpp3Reader()
            val pomModel = mavenXpp3Reader.read(FileReader(pomPath))
            logger.info("Download jar files to $outputDir...")
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
                logger.info("Downloaded jar files")
                logger.info("Generate dependency tree...")
                val treeCommand =
                    "mvn dependency:tree -DoutputFile=${treePath.absolutePathString()} -DoutputType=text -f $pomPath"
                logger.debug("execute tree command: $treeCommand")
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
                logger.info("Generated dependency tree")
            } catch (e: IOException) {
                logger.error("Error happened\n${e.message}")
                exitProcess(1)
            }

            logger.info("Parse dependency tree...")
            val inputType = InputType.TEXT
            val dependentTreeParser = inputType.newParser()
            val tree = dependentTreeParser.parse(treePath.inputStream().reader().buffered()) ?: run {
                logger.error("Failed to parse $treePath")
                exitProcess(1)
            }
            logger.info("Parsed dependency tree")

            logger.info("Analyze jar files...")
            val jars = outputDir.listDirectoryEntries("*.jar")
            logger.debug("jar list: ${jars.map { "$it, " }}")
            val urlClassLoader = URLClassLoader.newInstance(jars.map { it.toUri().toURL() }.toList().toTypedArray())
            jars.forEach { file ->
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val fileId = File.insert {
                        it[name] = file.fileName.toString()
                        it[path] = file.fileName.toString()
                    } get File.id

                    val dependenceJar =
                        pomModel.dependencies.find { "${it.artifactId}-${it.version}.jar" == file.fileName.toString() }
                            ?: run {
                                logger.debug("========== FIND DEPENDENCY TREE =============")
                                getArtifactInformation(tree.childNodes, file.fileName.toString()) ?: run {
                                    logger.error("Dependence jar file ${file.fileName} information not found.")
                                    exitProcess(1)
                                }
                            }
                    Jar.insert { jar ->
                        jar[groupId] =
                            dependenceJar.groupId
                        jar[artifactId] = dependenceJar.artifactId
                        jar[this.fileId] = fileId
                        jar[version] = dependenceJar.version
                    }
                    val jar = JarFile(file.toFile())
                    val url = file.toUri().toURL()
                    logger.debug("===== $url =====")

                    jar.entries().asSequence().filter { it.isDirectory.not() && it.name.endsWith(".class") }
                        .forEach { entry ->
                            val className = entry.name.substring(0, entry.name.length - 6).replace('/', '.')
                            try {
                                val clazz = urlClassLoader.loadClass(className)
                                logger.debug("class name: ${clazz.name}")
                                val classId = JarClass.insert { jarClass ->
                                    jarClass[package_name] = clazz.packageName
                                    jarClass[name] = clazz.name.removePrefix("${clazz.packageName}.")
                                    jarClass[modifiers] = Modifier.toString(clazz.modifiers)
                                    jarClass[this.fileId] = fileId
                                } get JarClass.id
                                clazz.methods.forEach {
                                    logger.debug("Method: ${it.name}")
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
                                clazz.fields.forEach {
                                    logger.debug("Field: ${it.name}")
                                    JarField.insert { jarField ->
                                        jarField[typeName] = it.type.name
                                        jarField[name] = it.name
                                        jarField[modifiers] = Modifier.toString(it.modifiers)
                                        jarField[this.classId] = classId
                                    }
                                }
                            } catch (e: Error) {
                                logger.error("${e.javaClass.name} : ${e.message}")
                            } catch (e: Exception) {
                                logger.error("${e.javaClass.name} : ${e.message}")
                            }
                        }
                }
            }
            logger.info("Analyzed jar files")

            logger.info("Analyze dependencies...")
            insertDependencies(tree.childNodes)
            logger.info("Analyzed dependencies")
        }

        fun findNodeFromFileName(nodes: List<Node>, fileName: String): Node? {
            return nodes.find { "${it.artifactId}-${it.version}.jar" == fileName } ?: run {
                val childNodes = nodes.map {
                    logger.debug("${it.artifactId} -> ${it.childNodes}")
                    it.childNodes
                }.flatten()
                logger.debug(childNodes.map { "$it, " }.toString())
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
                addLogger(Slf4jSqlDebugLogger)
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
    }
}
