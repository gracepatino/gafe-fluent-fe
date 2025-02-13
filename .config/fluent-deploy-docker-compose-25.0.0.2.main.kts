#!/usr/bin/env kotlin
@file:DependsOn("info.picocli:picocli:4.6.3")
@file:DependsOn("org.yaml:snakeyaml:1.33")
@file:DependsOn("com.google.crypto.tink:tink:1.7.0")

package fluent

import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import org.yaml.snakeyaml.Yaml
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Help
import picocli.CommandLine.Help.Ansi.OFF
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.lang.ProcessBuilder.Redirect
import java.lang.Thread.sleep
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Properties
import java.util.Scanner
import java.util.concurrent.Callable
import kotlin.collections.ArrayList
import kotlin.system.exitProcess

object Constants {
    const val IMAGE = "image"
    const val DB = "db"
    const val VOLUMES = "volumes"
    const val SERVICES = "services"
    const val BACKEND = "backend"
    const val FRONTEND = "frontend"

    const val PRIVATE_AWS_ECR = "012161395203.dkr.ecr.us-east-1.amazonaws.com"
    const val AWS_ECR = "public.ecr.aws"
}

@Command(
    name = "fluent-deploy-docker-compose", subcommands = [CommandLine.HelpCommand::class],
    description = ["Deploy helper script for Fluent Manager application"]
)
class DeployDockerCompose {
    @Spec
    lateinit var spec: CommandSpec

    @CommandLine.Option(
        names = ["--disable-interactivity"], type = [Boolean::class],
        scope = CommandLine.ScopeType.INHERIT,
        description = ["If set to true, will not ask questions and configurations, e.g. to overwrite a file. We don't recommend setting this option to true unless you are working in a scripted environment"]
    )
    var disableInteractivity: Boolean = false

    private val cliHelper: CliHelper by lazy { CliHelper(disableInteractivity) }

    companion object {
        internal const val APPLICATION_VERSION = "25.0.0.2"
        private const val APPLICATION_PROJECT_NAME_KEY = "com.docker.compose.project"
        internal val DEFAULT_REGISTRY = getDefaultRegistry(APPLICATION_VERSION)
        const val DOCKER_COMPOSE_YAML_FILE = "docker-compose.yml"
        private const val REMOVE_VOLUMES_SCRIPT_NAME = "remove_volumes.sh"
        private const val BACKUP_DB = "backup_db"
        const val ENV_FILE = ".env"

        const val FLUENT_SDK_TYPE = "fluent"

        private const val BACK_IMAGE_VERSION_TAG = APPLICATION_VERSION
        private const val FRONT_IMAGE_VERSION_TAG = APPLICATION_VERSION
        private const val DB_IMAGE_VERSION_TAG = APPLICATION_VERSION
        private const val COLON_SEPARATOR = ":"
        private const val CONTAINER_NAME = "container_name"

        private const val DOCKER_IMAGE_NAME_MANAGER_FRONTEND = "apryse/fluent-manager-frontend"
        private const val DOCKER_IMAGE_NAME_MANAGER_BACKEND = "apryse/fluent-manager-backend"
        private const val DOCKER_IMAGE_NAME_MANAGER_DB = "apryse/fluent-manager-db"

        var externalProcessHelper: ExternalProcessHelper = ProcessBuilderExternalProcessHelper()

        private const val ENV_KEY_JWT_TOKEN = "FLUENT_MANAGER_JWT_PRIVATE_KEY"
        private const val ENV_KEY_ADMIN_EMAIL = "FLUENT_MANAGER_DEFAULT_ADMIN_EMAIL"
        private const val ENV_KEY_ADMIN_PASSWORD = "FLUENT_MANAGER_DEFAULT_ADMIN_PASSWORD"

        private const val ENV_KEY_FLUENT_LICENSE_SUBSCRIPTION_ENABLE = "FLUENT_MANAGER_LICENSE_SUBSCRIPTION_ENABLE"
        private const val ENV_KEY_FLUENT_MANAGER_MAILING_ENABLE = "FLUENT_MANAGER_MAILING_ENABLE"
        private const val ENV_KEY_FLUENT_MANAGER_SMTP_HOST = "FLUENT_MANAGER_SMTP_HOST"
        private const val ENV_KEY_FLUENT_MANAGER_SMTP_PORT = "FLUENT_MANAGER_SMTP_PORT"
        private const val ENV_KEY_FLUENT_MANAGER_SMTP_USERNAME = "FLUENT_MANAGER_SMTP_USERNAME"
        private const val ENV_KEY_FLUENT_MANAGER_SMTP_PASSWORD = "FLUENT_MANAGER_SMTP_PASSWORD"
        private const val ENV_KEY_FLUENT_MANAGER_SMTP_FROM = "FLUENT_MANAGER_SMTP_FROM"
        private const val ENV_KEY_FLUENT_MANAGER_SMTP_AUTH = "FLUENT_MANAGER_SMTP_AUTH"
        private const val ENV_KEY_FLUENT_MANAGER_SMTP_TLS_ENABLE = "FLUENT_MANAGER_SMTP_TLS_ENABLE"
        private const val POSTGRES_PASSWORD = "POSTGRES_PASSWORD"
        const val ENV_KEY_SDK_TYPE = "FLUENT_MANAGER_SDK_TYPE"
        const val SPRING_PROFILES = "SPRING_PROFILES_ACTIVE"

        const val ENV_KEY_FLUENT_MANAGER_VAULT_ENABLE = "FLUENT_MANAGER_VAULT_ENABLE"
        const val DEFAULT_VALUE_ENV_KEY_FLUENT_MANAGER_VAULT_ENABLE = "false"
        const val ENV_KEY_FLUENT_MANAGER_VAULT_URI = "FLUENT_MANAGER_VAULT_URI"
        const val DEFAULT_VALUE_ENV_KEY_FLUENT_MANAGER_VAULT_URI = "http://host.docker.internal:8200"
        const val ENV_KEY_FLUENT_MANAGER_VAULT_TOKEN = "FLUENT_MANAGER_VAULT_TOKEN"
        const val DEFAULT_VALUE_ENV_KEY_FLUENT_MANAGER_VAULT_TOKEN = "static-token-value"
        const val ENV_KEY_FLUENT_MANAGER_VAULT_SECRET_ENGINE_PATH = "FLUENT_MANAGER_VAULT_SECRET_ENGINE_PATH"
        const val DEFAULT_VALUE_ENV_KEY_FLUENT_MANAGER_VAULT_SECRET_ENGINE_PATH = "fluent-manager"

        const val ENV_KEY_FLUENT_MANAGER_KEYSET_HANDLE = "FLUENT_MANAGER_KEYSET_HANDLE"

        const val ENV_KEY_FLUENT_MANAGER_PUBLIC_URL = "PUBLIC_URL"
        const val DEFAULT_PUBLIC_URL = "";

        private val dockerLabelsArgs = arrayOf("docker", "inspect", "--format", "'{{.Config.Labels}}'")

        val dbPass = generatePassword(12)

        private val DEFAULT_CONFIGURATION = mapOf(
            SPRING_PROFILES to "prod, $FLUENT_SDK_TYPE",
            (ENV_KEY_JWT_TOKEN) to generateJwtPrivateKey(),
            "FLUENT_MANAGER_ACCESS_TOKEN_TIME_TO_LIVE" to "86400",
            "FLUENT_MANAGER_REFRESH_TOKEN_TIME_TO_LIVE" to "1209600",
            "FLUENT_MANAGER_COOKIES_TIME_TO_LIVE" to "2592000",
            "FLUENT_MANAGER_MAXIMUM_FAILED_LOGIN_ATTEMPTS" to "5",
            "FLUENT_MANAGER_CORS_ALLOWED_PATHS" to "*",
            "FLUENT_MANAGER_CORS_ALLOWED_ORIGINS" to "*",
            "FLUENT_MANAGER_CORS_ALLOWED_METHODS" to "*",
            "FLUENT_MANAGER_DATABASE_URL" to "db:5432",
            "FLUENT_MANAGER_DATABASE_NAME" to "fluent",
            "FLUENT_MANAGER_DATABASE_USERNAME" to "postgres",
            "FLUENT_MANAGER_DATABASE_PASSWORD" to dbPass,
            "FLUENT_MANAGER_RESET_SYSTEM_ADMINISTRATOR_CREDENTIALS" to "false",
            (ENV_KEY_FLUENT_MANAGER_MAILING_ENABLE) to "false",
            (ENV_KEY_FLUENT_MANAGER_SMTP_HOST) to "smtp.gmail.com",
            (ENV_KEY_FLUENT_MANAGER_SMTP_PORT) to "587",
            (ENV_KEY_FLUENT_MANAGER_SMTP_USERNAME) to "admin@gmail.com",
            (ENV_KEY_FLUENT_MANAGER_SMTP_PASSWORD) to "\$\$uper\$\$ecret",
            (ENV_KEY_FLUENT_MANAGER_SMTP_FROM) to "admin@email.com",
            (ENV_KEY_FLUENT_MANAGER_SMTP_AUTH) to "true",
            (ENV_KEY_FLUENT_MANAGER_SMTP_TLS_ENABLE) to "true",
            "FLUENT_MANAGER_SENTRY_DSN" to "",
            "FLUENT_MANAGER_SENTRY_ENVIRONMENT" to "",
            ENV_KEY_FLUENT_LICENSE_SUBSCRIPTION_ENABLE to "true",
            "POSTGRES_DB" to "fluent",
            "POSTGRES_USER" to "postgres",
            (POSTGRES_PASSWORD) to dbPass,
            (ENV_KEY_ADMIN_EMAIL) to "admin@email.com",
            (ENV_KEY_ADMIN_PASSWORD) to "admin@email.com",
            (ENV_KEY_SDK_TYPE) to FLUENT_SDK_TYPE,
            (ENV_KEY_FLUENT_MANAGER_PUBLIC_URL) to "",
        )

        val pwd: String = Paths.get(".").normalize().toFile().absolutePath
        val dbName = DEFAULT_CONFIGURATION["FLUENT_MANAGER_DATABASE_NAME"]!!
        val dbUser = DEFAULT_CONFIGURATION["FLUENT_MANAGER_DATABASE_USERNAME"]!!
        var imageTagChecker: ImageTagAvailabilityChecker = DockerImageAvailabilityChecker()

        private fun generateJwtPrivateKey(): String {
            val random = SecureRandom()
            val sharedSecret = ByteArray(32)
            random.nextBytes(sharedSecret)
            return Base64.getEncoder().encodeToString(sharedSecret)
        }

        fun generateKeysetHandleSerializedValue(): String {
            AeadConfig.register()
            val keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            val keysetHandleSerializedValue = ByteArrayOutputStream().use {
                CleartextKeysetHandle.write(keysetHandle, JsonKeysetWriter.withOutputStream(it))
                it.toByteArray()
            }
            return Base64.getEncoder().encodeToString(keysetHandleSerializedValue)
        }

        private fun getDefaultRegistry(applicationVersion: String): String {
            return if (applicationVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+".toRegex())) {
                Constants.AWS_ECR
            } else {
                Constants.PRIVATE_AWS_ECR
            }
        }

        fun fillInConfigurationFile(
            file: File,
            adminEmail: String,
            adminPassword: String,
            publicUrl: String,
            mailingOptions: MailingOptions?,
            vaultOptions: VaultOptions? = null,
        ) {
            val resolvedConfiguration: Map<String, String> = resolveEnvConfiguration(
                adminEmail, adminPassword, publicUrl, mailingOptions, vaultOptions,
                DEFAULT_CONFIGURATION
            )
            EnvHelper.writeConfigurationFile(file, resolvedConfiguration, true)
        }

        private fun resolveEnvConfiguration(
            adminEmail: String, adminPassword: String,
            publicUrl: String,
            mailingOptions: MailingOptions?,
            vaultOptions: VaultOptions?,
            initialEnvConfiguration: Map<String, String> = DEFAULT_CONFIGURATION
        ): Map<String, String> {
            val resolvedConfiguration = initialEnvConfiguration.toMutableMap()
            resolvedConfiguration[ENV_KEY_ADMIN_EMAIL] = adminEmail
            resolvedConfiguration[ENV_KEY_ADMIN_PASSWORD] = adminPassword
            resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_PUBLIC_URL] = publicUrl
            resolvedConfiguration[ENV_KEY_SDK_TYPE] = FLUENT_SDK_TYPE
            resolvedConfiguration[SPRING_PROFILES] = "prod, $FLUENT_SDK_TYPE"
            if (mailingOptions != null) {
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_MAILING_ENABLE] = "true"
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_SMTP_HOST] = mailingOptions.smtpHost
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_SMTP_PORT] = mailingOptions.smtpPort
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_SMTP_USERNAME] = mailingOptions.smtpUsername
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_SMTP_PASSWORD] = mailingOptions.smtpPassword
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_SMTP_FROM] = mailingOptions.smtpFromAddress
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_SMTP_AUTH] = mailingOptions.smtpAuth.toString()
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_SMTP_TLS_ENABLE] = mailingOptions.smtpTlsEnable.toString()
            }
            if (vaultOptions != null) {
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_VAULT_ENABLE] = "true"
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_VAULT_URI] = vaultOptions.vaultUrl
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_VAULT_TOKEN] = vaultOptions.vaultToken
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_VAULT_SECRET_ENGINE_PATH] = vaultOptions.vaultSecretEnginePath
            } else {
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_VAULT_ENABLE] =
                    DEFAULT_VALUE_ENV_KEY_FLUENT_MANAGER_VAULT_ENABLE
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_VAULT_URI] =
                    DEFAULT_VALUE_ENV_KEY_FLUENT_MANAGER_VAULT_URI
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_VAULT_TOKEN] =
                    DEFAULT_VALUE_ENV_KEY_FLUENT_MANAGER_VAULT_TOKEN
                resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_VAULT_SECRET_ENGINE_PATH] =
                    DEFAULT_VALUE_ENV_KEY_FLUENT_MANAGER_VAULT_SECRET_ENGINE_PATH
            }
            resolvedConfiguration[ENV_KEY_FLUENT_MANAGER_KEYSET_HANDLE] = generateKeysetHandleSerializedValue()
            return resolvedConfiguration.toSortedMap()
        }

        fun generatePassword(pwdLen: Int): String {
            val lower = 'a'..'z'
            val upper = 'A'..'Z'
            val digit = '0'..'9'
            val other = arrayOf('$', '&', '+', ':', ';', '=', '?', '@', '#', '|', '\'', '<', '>', '-', '^', '*', '(', ')', '%', '!', ',', '.')

            val pwd = StringBuilder().apply {
                append(lower.random())
                append(upper.random())
                append(digit.random())
                append(other.random())
                val total = lower.joinToString("") +
                        upper.joinToString("") +
                        digit.joinToString("") +
                        other.joinToString("")
                for (j in 0 until pwdLen - 4) {
                    append(total.random())
                }
            }.toString()

            val pwdChars = pwd.toCharArray()
            repeat(3) { pwdChars.shuffle() }
            return pwdChars.concatToString()
        }

        private fun readDockerComposeContent(): Map<String, Any> {
            return File(DOCKER_COMPOSE_YAML_FILE).inputStream().use { Yaml().load(it) }
        }

        private fun extractImageName(imageReference: String): String {
            var imageName =
                if (imageReference.contains(COLON_SEPARATOR)) imageReference.split(
                    COLON_SEPARATOR)[0] else imageReference
            if (imageName.count { ("/".contains(it)) } == 2) {
                val registrySeparatorPos = imageName.indexOf('/')
                imageName = imageName.substring(registrySeparatorPos + 1)
            }
            return imageName
        }

        private fun extractImageVersion(imageReference: String): String {
            return if (imageReference.contains(COLON_SEPARATOR)) imageReference.split(COLON_SEPARATOR)[1] else ""
        }

        class MailingConfiguration {
            @CommandLine.Option(
                names = ["--enable-mailing"], type = [Boolean::class], required = true,
                description = ["Enable mailing capabilities. If this parameter is set to true, a bunch of other parameters (mailing options) need to be specified"]
            )
            var mailingEnabled: Boolean = true

            @CommandLine.ArgGroup(exclusive = false)
            var mailingOptions: MailingOptions? = null
        }

        class MailingOptions {
            @CommandLine.Option(names = ["--smtp-host"], required = true, description = ["SMTP host"])
            var smtpHost: String = ""

            @CommandLine.Option(names = ["--smtp-port"], required = false, description = ["SMTP port"])
            var smtpPort: String = "587"

            @CommandLine.Option(names = ["--smtp-username"], required = true, description = ["SMTP account username"])
            var smtpUsername: String = ""

            @CommandLine.Option(names = ["--smtp-password"], required = true, description = ["SMTP account password"])
            var smtpPassword: String = ""

            @CommandLine.Option(names = ["--smtp-from"], required = true, description = ["SMTP 'from' email address"])
            var smtpFromAddress: String = ""

            @CommandLine.Option(
                names = ["--smtp-auth"],
                required = false,
                type = [Boolean::class],
                description = ["Enable SMTP authentication"]
            )
            var smtpAuth: Boolean = true

            @CommandLine.Option(
                names = ["--smtp-tls-enable"],
                required = false,
                type = [Boolean::class],
                description = ["Enable SMTP TLS"]
            )
            var smtpTlsEnable: Boolean = true
        }

        class VaultConfiguration {
            @CommandLine.Option(
                names = ["--enable-vault"], type = [Boolean::class], required = true,
                description = ["Enable vault capabilities. If this parameter is set to true, a bunch of other parameters (vault options) need to be specified"]
            )
            var vaultEnabled: Boolean = false

            @CommandLine.ArgGroup(exclusive = false)
            var vaultOptions: VaultOptions? = null
        }

        class VaultOptions {
            @CommandLine.Option(names = ["--vault-url"], required = true, description = ["Vault URL"])
            var vaultUrl: String = ""

            @CommandLine.Option(names = ["--vault-token"], required = true, description = ["Vault token to perform authentication"])
            var vaultToken: String = ""

            @CommandLine.Option(names = ["--vault-secret-engine-path"], required = true, description = ["Vault secret engine path"])
            var vaultSecretEnginePath: String = ""
        }

        class ImageVersionOverrideOptions {
            @CommandLine.Option(
                names = ["--overridden-manager-frontend-version"], hidden = true,
                description = ["Overridden version of the Manager Frontend image. Falls back to default one if not available"]
            )
            var overriddenManagerFrontendVersion: String = ""

            @CommandLine.Option(
                names = ["--overridden-manager-backend-version"], hidden = true,
                description = ["Overridden version of the Manager Backend image. Falls back to default one if not available"]
            )
            var overriddenManagerBackendVersion: String = ""

            @CommandLine.Option(
                names = ["--overridden-manager-db-version"], hidden = true,
                description = ["Overridden version of the Manager DB image. Falls back to default one if not available"]
            )
            var overriddenManagerDBVersion: String = ""
        }

        private fun fileExists(fileName: String): Boolean {
            return File(fileName).exists()
        }

        private fun checkDockerComposeFileExists() {
            if (!fileExists(DOCKER_COMPOSE_YAML_FILE)) {
                println("$DOCKER_COMPOSE_YAML_FILE file doesn't exist")
                exitProcess(1)
            }
        }

        private fun checkEnvExists() {
            if (!fileExists(ENV_FILE)) {
                println("$ENV_FILE file doesn't exist")
                exitProcess(1)
            }
        }

        private fun getFilesNames(folder: String, pattern: Regex): List<String> {
            val foundFiles = ArrayList<String>()

            Paths.get(Paths.get(".").normalize().toFile().absolutePath, folder).toFile().listFiles { _, name ->
                pattern.containsMatchIn(name)
            }!!.map { file -> file.name }.toCollection(foundFiles)

            return foundFiles
        }

        private fun createShellScript(requestedFileName: String) {
            File(REMOVE_VOLUMES_SCRIPT_NAME).writeText(
                """\
                rm -rf /volume/* /volume/..?* /volume/.[!.]*
                tar -C /volume/ -xjf /tmp/$requestedFileName
                """.trimIndent()
            )
        }

        private fun removeShellScript(): Boolean {
            return File(REMOVE_VOLUMES_SCRIPT_NAME).delete()
        }

        private fun compareConfigurationMaps(
            serverConfiguration: Map<String, String>,
            scriptConfiguration: Map<String, String>
        ): Boolean {
            if (serverConfiguration.size != scriptConfiguration.size) {
                return false
            }
            for (key in serverConfiguration.keys) {
                when (key) {
                    ENV_KEY_JWT_TOKEN -> {
                        // For JWT tokens, only compare lengths as they are generated randomly
                        if (serverConfiguration[key]!!.length != scriptConfiguration[key]!!.length) {
                            return false
                        }
                    }

                    else -> {
                        if (serverConfiguration[key]!! != scriptConfiguration[key]!!) {
                            return false
                        }
                    }
                }
            }
            return true
        }

        private fun checkEnvFileConfiguration(resolvedConfiguration: Map<String, String>): Boolean {
            val file = File(ENV_FILE)
            if (file.exists()) {
                val properties = Properties()
                file.inputStream().use { properties.load(it) }
                @Suppress("UNCHECKED_CAST")
                val previousConfiguration = properties.toMap() as Map<String, String>

                return compareConfigurationMaps(previousConfiguration, resolvedConfiguration)
            }
            return false
        }

        private fun overrideImageReferenceIfAvailableOrDefault(
            baseImageName: String,
            baseImageReference: String,
            newImageVersion: String,
            registry: String
        ) = imageTagChecker.overrideImageReferenceIfAvailableOrDefault(
            baseImageName,
            baseImageReference,
            newImageVersion,
            registry
        )


        private fun createYamlFile(
            port: String,
            alias: String?,
            registry: ImageRegistry,
            appFilePath: String,
            imageVersionOverrideOptions: ImageVersionOverrideOptions?
        ) {
            val aliasPrefix = if (alias != null) "-$alias" else ""
            val yamlContent = buildYamlContent(registry, port, aliasPrefix, imageVersionOverrideOptions)
            val dockerComposeFile = File(appFilePath)
            dockerComposeFile.writeText(yamlContent)
            dockerComposeFile.createNewFile()
            println("Application file has been successfully created at $appFilePath")
        }

        internal fun createImageReference(registry: String?, imageName: String, imageVersion: String): String {
            return "${if (registry?.isNotBlank() == true) "$registry/" else ""}${imageName}:${imageVersion}"
        }

        internal fun buildYamlContent(
            registry: ImageRegistry,
            port: String,
            alias: String,
            imageVersionOverrideOptions: ImageVersionOverrideOptions?
        ): String {
            var frontendImageReference = createImageReference(
                registry.managerFrontendRegistry, DOCKER_IMAGE_NAME_MANAGER_FRONTEND, FRONT_IMAGE_VERSION_TAG
            )
            if (imageVersionOverrideOptions?.overriddenManagerFrontendVersion != null) {
                frontendImageReference = overrideImageReferenceIfAvailableOrDefault(
                    DOCKER_IMAGE_NAME_MANAGER_FRONTEND,
                    frontendImageReference,
                    imageVersionOverrideOptions.overriddenManagerFrontendVersion,
                    registry.managerFrontendRegistry
                )
            }

            var backendImageReference = createImageReference(
                registry.managerBackendRegistry, DOCKER_IMAGE_NAME_MANAGER_BACKEND, BACK_IMAGE_VERSION_TAG
            )
            if (imageVersionOverrideOptions?.overriddenManagerBackendVersion != null) {
                backendImageReference = overrideImageReferenceIfAvailableOrDefault(
                    DOCKER_IMAGE_NAME_MANAGER_BACKEND,
                    backendImageReference,
                    imageVersionOverrideOptions.overriddenManagerBackendVersion,
                    registry.managerBackendRegistry
                )
            }

            var dbImageReference = createImageReference(
                registry.managerDBRegistry, DOCKER_IMAGE_NAME_MANAGER_DB, DB_IMAGE_VERSION_TAG
            )
            if (imageVersionOverrideOptions?.overriddenManagerDBVersion != null) {
                dbImageReference = overrideImageReferenceIfAvailableOrDefault(
                    DOCKER_IMAGE_NAME_MANAGER_DB,
                    dbImageReference,
                    imageVersionOverrideOptions.overriddenManagerDBVersion,
                    registry.managerDBRegistry
                )
            }

            val appContentBase = """
                version: "3"
                services:
                  frontend:
                    container_name: "fluent-manager-frontend$alias"
                    restart: "always"
                    image: "$frontendImageReference"
                    env_file: "$ENV_FILE"
                    ports:
                    - "$port:8080"
                    depends_on:
                    - "backend"
                    """
            val appContentServices = """
                    logging:
                      driver: "json-file"
                      options:
                        max-size: "50m"
                        max-file: "3"
                  backend:
                    container_name: "fluent-manager-backend$alias"
                    restart: "always"
                    image: "$backendImageReference"
                    env_file: "$ENV_FILE"
                    depends_on:
                    - "db"
                    logging:
                      driver: "json-file"
                      options:
                        max-size: "50m"
                        max-file: "3"
                  db:
                    container_name: "fluent-manager-db$alias"
                    restart: "always"
                    image: "$dbImageReference"
                    volumes:
                    - "fluent-manager-db$alias:/var/lib/postgresql/data"
                    env_file: "$ENV_FILE"
                    logging:
                      driver: "json-file"
                      options:
                        max-size: "50m"
                        max-file: "3"
                  """
            val appConfigVolumesBase = """   
                volumes:
                  fluent-manager-db$alias:
                    name: "fluent-manager-db$alias"
                """
            val appConfigNetwork = """
                networks:
                  default:
                    name: "fluent-manager-network$alias"
            """

            val appContent = "$appContentBase$appContentServices$appConfigVolumesBase$appConfigNetwork".trimIndent()
            return AppConfigHelper.convertYamlObjectToString(Yaml().load(appContent))
        }

        private fun getRegistryUsingImageVersionOverrideOptions(
            propertyValue: String?,
            userProvidedRegistry: String?,
            defaultVersion: String,
            defaultRegistry: String
        ): String {
            val property = if (propertyValue.isNullOrBlank()) defaultVersion else propertyValue
            return if (property.isBlank()) {
                userProvidedRegistry ?: defaultRegistry
            } else {
                userProvidedRegistry ?: getDefaultRegistry(property)
            }
        }

        private fun awaitEmptyOutput(vararg args: String) {
            val soutStream = ByteArrayOutputStream()
            val serrStream = ByteArrayOutputStream()
            externalProcessHelper.executeProcessAndRedirectOutput(soutStream, serrStream, *args)
            while (soutStream.toString("UTF-8").isNotEmpty()) {
                sleep(1000)
                soutStream.reset()
                serrStream.reset()
                externalProcessHelper.executeProcessAndRedirectOutput(soutStream, serrStream, *args)
            }
        }

        private fun awaitTerminationAndCopyFileFromContainer(containerId: String, backupFileName: String) {
            awaitEmptyOutput("docker", "ps", "-f", "id=${containerId}", "-q")

            externalProcessHelper.executeProcess(
                "docker",
                "cp",
                "${containerId}:/tmp/${backupFileName}",
                "${pwd}/${BACKUP_DB}"
            )
            externalProcessHelper.executeProcess("docker", "container", "rm", "-f", containerId)
        }
    }

    class ImageRegistry(registry: String? = null) {
        var managerFrontendRegistry: String = registry ?: DEFAULT_REGISTRY
        var managerBackendRegistry: String = registry ?: DEFAULT_REGISTRY
        var managerDBRegistry: String = registry ?: DEFAULT_REGISTRY
    }

    @Command(
        name = "deploy",
        description = ["Deploys the application using the Docker Compose tool (up action in Docker Compose terminology) using the docker-compose.yml as application file and .env as configuration file"]
    )
    private fun commandDeploy() {
        checkDockerComposeFileExists()
        checkEnvExists()
        externalProcessHelper.executeProcess("docker", "compose", "up", "-d", "--force-recreate")
    }

    @Command(
        name = "undeploy",
        description = ["Undeploys the application using the Docker Compose tool using the docker-compose.yml file as application file. Current containers and bridge network will be removed. Data in containers that is not persisted by any volumes will be lost"]
    )
    private fun commandUndeploy() {
        checkDockerComposeFileExists()
        checkEnvExists()
        val containerName = getRequiredDataFromDockerComposeFile(CONTAINER_NAME)
        val projectName = getProjectNameWithDockerInspect(containerName)
        externalProcessHelper.executeProcess("docker", "compose", "-p", projectName, "down")
    }

    @Command(
        name = "start",
        description = ["Starts the application using the docker Compose tool using the docker-compose.yml file as application file"]
    )
    private fun commandStart() {
        checkDockerComposeFileExists()
        checkEnvExists()
        externalProcessHelper.executeProcess("docker", "compose", "start")
    }

    @Command(
        name = "stop",
        description = ["Stops the application using the Docker Compose tool using the docker-compose.yml file as application file"]
    )
    private fun commandStop() {
        checkDockerComposeFileExists()
        checkEnvExists()
        externalProcessHelper.executeProcess("docker", "compose", "stop")
    }

    @Command(
        name = "db-dump",
        description = ["Creates database dump using pg_dump tool. Backup file will be stored in the 'backup_db' directory"]
    )
    private fun commandDbDump() {
        checkDockerComposeFileExists()
        val dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm"))
        val dockerComposeYamlContent = readDockerComposeContent()
        val networkName =
            ((dockerComposeYamlContent["networks"] as Map<*, *>)["default"] as Map<*, *>)["name"].toString()
        val dbContainerName =
            ((dockerComposeYamlContent[Constants.SERVICES] as Map<*, *>)[Constants.DB] as Map<*, *>)["container_name"].toString()
        val dbImageName =
            ((dockerComposeYamlContent[Constants.SERVICES] as Map<*, *>)[Constants.DB] as Map<*, *>)[Constants.IMAGE].toString()
        val backupFileName = "${dateTime}-dump_db.tar"
        val backupDir = File(pwd, BACKUP_DB)
        if (!backupDir.exists()) {
            backupDir.mkdir()
        }
        val soutStream = ByteArrayOutputStream()
        val serrStream = ByteArrayOutputStream()
        externalProcessHelper.executeProcessAndRedirectOutput(
            soutStream,
            serrStream,
            "docker",
            "run",
            "-d",
            "--network=${networkName}",
            "-w=/tmp",
            "-e=PGPASSWORD=${getEnvFileProperty(POSTGRES_PASSWORD)}",
            dbImageName,
            "pg_dump",
            "-h",
            dbContainerName,
            "-Ft",
            "-f",
            backupFileName,
            "-U",
            dbUser,
            dbName
        )
        awaitTerminationAndCopyFileFromContainer(soutStream.toString("UTF-8").trimIndent(), backupFileName)
        println("${BACKUP_DB}/${backupFileName} has been successfully created...")
    }

    private fun getEnvFileProperty(propertyName: String): String {
        val file = File(ENV_FILE)
        if (file.exists()) {
            val properties = Properties()
            file.inputStream().use { properties.load(it) }
            @Suppress("UNCHECKED_CAST")
            val previousConfiguration = properties.toMap() as Map<String, String>

            return previousConfiguration[propertyName].toString()
        }
        return ""
    }

    @Command(
        name = "db-full-backup",
        description = ["Creates full backup of the current database by storing all database files. Backup file will be stored in the 'backup_db' directory. Important! The application will be stopped for the time of backup and restarted after backup has completed"]
    )
    private fun commandDbFullBackup() {
        checkDockerComposeFileExists()
        val dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_hh_mm"))
        val dockerComposeYamlContent = readDockerComposeContent()
        val fullDatabaseVolume =
            ((dockerComposeYamlContent[Constants.SERVICES] as Map<*, *>)[Constants.DB] as Map<*, *>)[Constants.VOLUMES].toString()
        val databaseVolume = fullDatabaseVolume.split(':')[0].replace("[", "")
        val dbImageName =
            ((dockerComposeYamlContent[Constants.SERVICES] as Map<*, *>)[Constants.DB] as Map<*, *>)[Constants.IMAGE].toString()
        val backupFileName = "${dateTime}-backup.tar.bz2"
        val backupDir = File(pwd, BACKUP_DB)
        if (!backupDir.exists()) {
            backupDir.mkdir()
        }

        externalProcessHelper.executeProcess("docker", "compose", "stop")
        val soutStream = ByteArrayOutputStream()
        val serrStream = ByteArrayOutputStream()
        externalProcessHelper.executeProcessAndRedirectOutput(
            soutStream, serrStream, "docker", "run", "-d", "-v=${databaseVolume}:/volume", "-w=/tmp",
            dbImageName, "tar", "-cjf", backupFileName, "-C", "/volume", "./"
        )
        awaitTerminationAndCopyFileFromContainer(soutStream.toString("UTF-8").trimIndent(), backupFileName)
        externalProcessHelper.executeProcess("docker", "compose", "start")
        println("${BACKUP_DB}/${backupFileName} has been successfully created...")
    }

    @Command(
        name = "db-dump-restore",
        description = ["Restores the current database from dump using pg_restore tool. Backup file for restore will be picked from the 'backup_db' directory"]
    )
    private fun commandDbDumpRestore() {
        checkDockerComposeFileExists()

        val dockerComposeYamlContent = readDockerComposeContent()
        val networkName =
            ((dockerComposeYamlContent["networks"] as Map<*, *>)["default"] as Map<*, *>)["name"].toString()
        val dbContainerName =
            ((dockerComposeYamlContent[Constants.SERVICES] as Map<*, *>)[Constants.DB] as Map<*, *>)["container_name"].toString()
        val dbImageName =
            ((dockerComposeYamlContent[Constants.SERVICES] as Map<*, *>)[Constants.DB] as Map<*, *>)[Constants.IMAGE].toString()

        val foundFiles = getFilesNames(BACKUP_DB, Regex(".tar$"))
        if (foundFiles.isEmpty()) {
            println("No files was found")
            return
        }

        foundFiles.forEach { println("fileName to restore: $it") }
        println("Type file name to restore: ")

        val requestedFileName = readLine()
        if (foundFiles.contains(requestedFileName)) {
            println("Restoring from $requestedFileName...")
            externalProcessHelper.executeProcess(
                "docker",
                "run",
                "--network=$networkName",
                "-e",
                "PGPASSWORD=${getEnvFileProperty(POSTGRES_PASSWORD)}",
                "-v",
                "$pwd/$BACKUP_DB:/tmp",
                "--rm",
                dbImageName,
                "pg_restore",
                "-h",
                dbContainerName,
                "-d",
                dbName,
                "/tmp/$requestedFileName",
                "-c",
                "-Ft",
                "-U",
                dbUser
            )
            return
        }
        println("Aborted")
    }

    @Command(
        name = "db-full-backup-restore",
        description = ["Restores the database from full backup by restoring all database files. Backup file for restore will be picked from the 'backup_db' directory. Important! The application will be stopped for the time of backup and restarted after backup has completed"]
    )
    private fun commandDbFullBackupRestore() {
        checkDockerComposeFileExists()
        val dockerComposeYamlContent = readDockerComposeContent()

        @Suppress("UNCHECKED_CAST")
        val fullDatabaseVolume =
            (((dockerComposeYamlContent[Constants.SERVICES] as Map<*, *>)[Constants.DB] as Map<*, *>)[Constants.VOLUMES] as List<String>)[0]
        val databaseVolume = fullDatabaseVolume.split(COLON_SEPARATOR)[0]
        val dbImageName =
            ((dockerComposeYamlContent[Constants.SERVICES] as Map<*, *>)[Constants.DB] as Map<*, *>)[Constants.IMAGE].toString()

        val foundFiles = getFilesNames(BACKUP_DB, Regex(".tar.bz2$"))
        if (foundFiles.isEmpty()) {
            println("No files to full restore was found")
            return
        }

        foundFiles.forEach { println("fileName to full restore: $it") }
        println("Type file name to full restore: ")

        val requestedFileName = readLine()!!
        if (foundFiles.contains(requestedFileName)) {
            println("Restoring from $requestedFileName...")
            externalProcessHelper.executeProcess("docker", "compose", "stop")
            try {
                createShellScript(requestedFileName)
                externalProcessHelper.executeProcess(
                    "docker",
                    "run",
                    "-v",
                    "$databaseVolume:/volume",
                    "-v",
                    "$pwd/$BACKUP_DB:/tmp",
                    "-v",
                    "$pwd/$REMOVE_VOLUMES_SCRIPT_NAME:/script/$REMOVE_VOLUMES_SCRIPT_NAME",
                    "--rm",
                    dbImageName,
                    "sh",
                    "/script/$REMOVE_VOLUMES_SCRIPT_NAME"
                )
            } finally {
                removeShellScript()
            }
            externalProcessHelper.executeProcess("docker", "compose", "start")
            return
        }
        println("Aborted")
    }

    @Command(
        name = "remove",
        description = ["Completely removes the installed application. This action can't be reversed! Please note that this action will result in data loss. The application will be stopped. All application data, including Docker volumes and images will be removed"]
    )
    private fun commandRemove() {
        if (cliHelper.askYesNoQuestion(
                "Do you really want to perform application removal action? All data will be irreversibly lost",
                isYesDefault = true, emptyAnswerAsDefault = false
            )
        ) {
            checkDockerComposeFileExists()
            val containerName = getRequiredDataFromDockerComposeFile(CONTAINER_NAME)
            val projectName = getProjectNameWithDockerInspect(containerName)
            externalProcessHelper.executeProcess("docker", "compose", "-p", projectName, "down", "--rmi", "all", "-v")
        } else {
            println("Removal action has been cancelled by the user")
        }
    }

    @Command(
        name = "create-app-file",
        showDefaultValues = true,
        description = ["Creates Docker Compose application file with application set up in YAML format ($DOCKER_COMPOSE_YAML_FILE)"]
    )
    private fun commandCreateAppFile(
        @CommandLine.Option(
            names = ["--alias"],
            description = ["container alias; useful for deploying several application instances on one physical node"]
        )
        alias: String,
        @CommandLine.Option(
            names = ["--host-port"],
            type = [Int::class],
            description = ["host port"],
            defaultValue = "80"
        )
        port: Int,
        @CommandLine.Option(
            names = ["--registry"],
            hidden = true,
            description = ["custom registry to pull images from"]
        )
        registry: String?,
        @CommandLine.Option(
            names = ["--refresh-images"],
            type = [Boolean::class],
            defaultValue = "false",
            hidden = true,
            description = ["refreshes images from the upstream registry in case they are outdated"]
        )
        refreshImages: Boolean?,
        @CommandLine.Option(
            names = ["--app-file-path"],
            required = false,
            defaultValue = DOCKER_COMPOSE_YAML_FILE,
            hidden = true
        )
        appFilePath: String,
        @CommandLine.ArgGroup(exclusive = false) imageVersionOverrideOptions: ImageVersionOverrideOptions?
        ) {
        val imageRegistry = ImageRegistry()
        imageRegistry.managerFrontendRegistry = getRegistryUsingImageVersionOverrideOptions(
            imageVersionOverrideOptions?.overriddenManagerFrontendVersion,
            registry,
            FRONT_IMAGE_VERSION_TAG,
            DEFAULT_REGISTRY
        )
        imageRegistry.managerBackendRegistry = getRegistryUsingImageVersionOverrideOptions(
            imageVersionOverrideOptions?.overriddenManagerBackendVersion,
            registry,
            BACK_IMAGE_VERSION_TAG,
            DEFAULT_REGISTRY
        )
        imageRegistry.managerDBRegistry = getRegistryUsingImageVersionOverrideOptions(
            imageVersionOverrideOptions?.overriddenManagerDBVersion,
            registry,
            DB_IMAGE_VERSION_TAG,
            DEFAULT_REGISTRY
        )

        val dockerComposeYaml = File(appFilePath)
        if (dockerComposeYaml.exists()) {
            if (cliHelper.askYesNoQuestion(
                    "$appFilePath already exists. Do you want to replace file?",
                    isYesDefault = true, emptyAnswerAsDefault = false
                )
            ) {
                createYamlFile(port.toString(), alias, imageRegistry, appFilePath, imageVersionOverrideOptions)
            } else {
                println("Create yaml action has been cancelled by the user")
            }
        } else {
            createYamlFile(port.toString(), alias, imageRegistry, appFilePath, imageVersionOverrideOptions)
        }
        if (refreshImages == true) {
            println("Refreshing images...")
            externalProcessHelper.executeProcess("docker", "compose", "pull")
        }
    }

    @Command(
        name = "create-config-file",
        showDefaultValues = true,
        description = ["Creates the file with environment variables (.env) defining application configuration"]
    )
    private fun commandCreateConfigFile(
        @CommandLine.Option(
            names = ["--admin-email"],
            required = true,
            description = ["Email of the initial admin user"]
        )
        adminEmail: String,
        @CommandLine.Option(
            names = ["--admin-password"],
            required = true,
            description = ["Password of the initial admin user"]
        )
        adminPassword: String,
        @CommandLine.Option(
            names = ["--public-url"],
            required = false,
            description = ["Fluent Manager public url"],
            defaultValue = DEFAULT_PUBLIC_URL
        )
        publicUrl: String,
        @CommandLine.Option(names = ["--config-file-path"], required = false, defaultValue = ENV_FILE)
        configFilePath: String = ENV_FILE,
        @CommandLine.ArgGroup(exclusive = false, heading = "Mailing configuration\n")
        mailingConfig: MailingConfiguration?,
        @CommandLine.ArgGroup(exclusive = false, heading = "Vault configuration\n")
        vaultConfig: VaultConfiguration?
    ) {
        if (adminPassword.length < 12 || adminPassword.length > 64) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "Password must have length between 12 and 64 characters"
            )
        }
        val mailingOptions = resolveMailingOptions(mailingConfig)
        val vaultOptions = resolveVaultOptions(vaultConfig)

        val envFile = File(configFilePath)
        if (!envFile.exists()) {
            fillInConfigurationFile(envFile, adminEmail, adminPassword, publicUrl, mailingOptions, vaultOptions)
            println("Configuration file ($configFilePath) has been successfully created")
        } else {
            val isEnvSameToCurrentConfiguration = checkEnvFileConfiguration(
                resolveEnvConfiguration(adminEmail, adminPassword, publicUrl, mailingOptions, vaultOptions)
            )
            if (isEnvSameToCurrentConfiguration) {
                println("Configuration file ($configFilePath) exists and is up to date")
            } else {
                if (cliHelper.askYesNoQuestion(
                        "Configuration file ($configFilePath) exists but is outdated. Would you like to update configuration file? Warning! Previous configuration will be permanently lost during update",
                        true,
                        false
                    )
                ) {
                    fillInConfigurationFile(envFile, adminEmail, adminPassword, publicUrl, mailingOptions, vaultOptions)
                    println("Configuration file($configFilePath) is successfully updated")
                } else {
                    println("Configuration file update has been cancelled by the user")
                }
            }
        }
    }

    private fun getRequiredDataFromDockerComposeFile(requiredKey: String): String {
        val dockerComposeYamlContent = readDockerComposeContent()
        val composeFileString =
            ((dockerComposeYamlContent[Constants.SERVICES] as Map<*, *>)[Constants.FRONTEND] as Map<*, *>)[requiredKey] as String

        return composeFileString
    }

    private fun getProjectNameWithDockerInspect(containerName: String): String {
        val soutStream = ByteArrayOutputStream()
        val serrStream = ByteArrayOutputStream()
        externalProcessHelper.executeProcessAndRedirectOutput(soutStream, serrStream, *dockerLabelsArgs, containerName)

        val map = soutStream.toString(Charsets.UTF_8.name()).trimIndent()
            .substringAfter("map[").substringBefore("]").split(" ")
            .filter { it.contains(":") }.associate {
                val (left, right) = it.split(":")
                left to right
            }
        val projectName = map.get(APPLICATION_PROJECT_NAME_KEY).toString()

        val errorLog = String(serrStream.toByteArray(), Charsets.UTF_8)
        if (errorLog.isNotEmpty()) {
            println(errorLog)
        }
        return projectName
    }

    @Command(
        name = "update",
        description = ["Updates application. Note! Make sure to back up your data before doing an update"]
    )
    private fun commandUpdate(
        @CommandLine.Option(
            names = ["--refresh-images"],
            type = [Boolean::class],
            defaultValue = "false",
            hidden = true,
            description = ["refreshes images from the upstream registry in case they are outdated"]
        )
        refreshImages: Boolean,
        @CommandLine.Option(
            names = ["--registry"],
            hidden = true,
            description = ["custom registry to pull images from"]
        )
        registry: String?
    ) {
        val currentRegistry = registry ?: DEFAULT_REGISTRY
        checkDockerComposeFileExists()
        checkEnvExists()

        val dockerComposeFile = File(DOCKER_COMPOSE_YAML_FILE)
        val dockerComposeYamlContent = readDockerComposeContent()

        val frontImage =
            ((dockerComposeYamlContent[Constants.SERVICES] as Map<*, *>)[Constants.FRONTEND] as Map<*, *>)[Constants.IMAGE] as String
        val backImage =
            ((dockerComposeYamlContent[Constants.SERVICES] as Map<*, *>)[Constants.BACKEND] as Map<*, *>)[Constants.IMAGE] as String
        val dbImage =
            ((dockerComposeYamlContent[Constants.SERVICES] as Map<*, *>)[Constants.DB] as Map<*, *>)[Constants.IMAGE] as String

        val frontImageVersion = Version.parseVersion(extractImageVersion(frontImage))
        val backImageVersion = Version.parseVersion(extractImageVersion(backImage))
        val dbImageVersion = Version.parseVersion(extractImageVersion(dbImage))

        val frontImageName = extractImageName(frontImage)
        val backImageName = extractImageName(backImage)
        val dbImageName = extractImageName(dbImage)

        performApplicationUpdate(
            ImageInfo(frontImage, frontImageName, frontImageVersion),
            ImageInfo(backImage, backImageName, backImageVersion),
            ImageInfo(dbImage, dbImageName, dbImageVersion),
            dockerComposeFile,
            refreshImages,
            currentRegistry
        )
    }

    private fun resolveMailingOptions(mailingConfig: MailingConfiguration?): MailingOptions? {
        return if (mailingConfig?.mailingEnabled == true) {
            mailingConfig.mailingOptions ?: throw CommandLine.ParameterException(spec.commandLine(), "If mailing capabilities are enabled, mailing options must be specified")
        } else {
            null
        }
    }

    private fun resolveVaultOptions(vaultConfig: VaultConfiguration?): VaultOptions? {
        return if (vaultConfig?.vaultEnabled == true) {
            vaultConfig.vaultOptions ?: throw CommandLine.ParameterException(spec.commandLine(), "If Vault capabilities are enabled, Vault options must be specified")
        } else {
            null
        }
    }

    private fun performApplicationUpdate(
        frontImageInfo: ImageInfo,
        backImageInfo: ImageInfo,
        dbImageInfo: ImageInfo,
        dockerComposeFile: File,
        refreshImages: Boolean,
        registry: String
    ) {
        var yamlContent = dockerComposeFile.readText()
        yamlContent = yamlContent.replace(
            frontImageInfo.imageSectionContent,
            createImageReference(registry, frontImageInfo.imageName, FRONT_IMAGE_VERSION_TAG)
        )
        yamlContent = yamlContent.replace(
            backImageInfo.imageSectionContent,
            createImageReference(registry, backImageInfo.imageName, BACK_IMAGE_VERSION_TAG)
        )
        yamlContent = yamlContent.replace(
            dbImageInfo.imageSectionContent,
            createImageReference(registry, dbImageInfo.imageName, DB_IMAGE_VERSION_TAG)
        )

        dockerComposeFile.writeText(yamlContent)

        println("docker_compose.yaml is successfully updated")

        if (refreshImages) {
            println("Refreshing images...")
            externalProcessHelper.executeProcess("docker", "compose", "pull")
        }

        commandUndeploy()
        commandDeploy()
        println("Update is successfully finished")
    }
}

data class ImageInfo(val imageSectionContent: String, val imageName: String, val imageVersion: Version)

class AppConfigHelper() {
    companion object {
        fun convertYamlObjectToString(yamlObject: Any): String {
            return Yaml().dump(yamlObject)
                .replace("- \"\"", "")
                .replace("\"backend\"", Constants.BACKEND)
                .replace("\"db\"", Constants.DB)
                .replace("/\\s-/".toRegex(), "   -")
        }
    }
}

class EnvHelper() {
    companion object {
        fun writeConfigurationFile(file: File, configuration: Map<String, String>, rewriteFile: Boolean = true) {
            val configurationFileContentBuilder = StringBuilder()
            configuration.forEach { entry ->
                configurationFileContentBuilder.append(entry.key + '=' + entry.value + System.lineSeparator())
            }
            if (rewriteFile) {
                file.writeText(configurationFileContentBuilder.toString())
            } else {
                file.appendText(configurationFileContentBuilder.toString())
            }
        }
    }
}

class CliHelper(private val disableInteractivity: Boolean) {

    private fun askQuestion(question: String, defaultAnswer: String, emptyAnswerAsDefault: Boolean): String {
        return if (disableInteractivity) {
            defaultAnswer
        } else {
            //Uses err here since some scripts use output to produce result
            System.err.println("$question ")
            if (emptyAnswerAsDefault) {
                System.err.println("[$defaultAnswer] ")
            }
            val answer = readLine()
            return if (emptyAnswerAsDefault && ((answer == null) || answer.isEmpty())) defaultAnswer else answer ?: ""
        }
    }

    fun askYesNoQuestion(question: String, isYesDefault: Boolean, emptyAnswerAsDefault: Boolean): Boolean {
        while (true) {
            val answer = askQuestion("$question [y/n]", if (isYesDefault) "Yes" else "No", emptyAnswerAsDefault)
            when {
                answer.matches("[Y|y](?:[E|e][S|s])?".toRegex()) -> return true
                answer.matches("[N|n][O|o]?".toRegex()) -> return false
            }
        }
    }
}

interface ImageTagAvailabilityChecker {
    fun overrideImageReferenceIfAvailableOrDefault(
        baseImageName: String,
        baseImageReference: String,
        newImageVersion: String,
        registry: String
    ): String
}

class DockerImageAvailabilityChecker : ImageTagAvailabilityChecker {
    override fun overrideImageReferenceIfAvailableOrDefault(
        baseImageName: String,
        baseImageReference: String,
        newImageVersion: String,
        registry: String
    ): String {
        val candidateReference = DeployDockerCompose.createImageReference(registry, baseImageName, newImageVersion)
        val soutStream = ByteArrayOutputStream()
        val serrStream = ByteArrayOutputStream()
        return try {
            DeployDockerCompose.externalProcessHelper.executeProcessAndRedirectOutput(
                soutStream,
                serrStream,
                "docker",
                "manifest",
                "inspect",
                candidateReference
            )
            candidateReference
        } catch (ignored: Exception) {
            println("Could not find image $candidateReference. Falling back to default reference $baseImageReference")
            println("Original Docker error message: ${String(soutStream.toByteArray(), StandardCharsets.UTF_8)}")
            println(String(serrStream.toByteArray(), StandardCharsets.UTF_8))
            baseImageReference
        }
    }
}

data class Version(val x: Int, val y: Int, val z: Int) : Comparable<Version> {

    companion object {

        fun parseVersion(version: String): Version {
            return version
                .replace("-SNAPSHOT", "")
                .split(".")
                .let {
                    Version(it[0].toInt(), it[1].toInt(), it[2].toInt())
                }
        }

        fun isInRangeStrictLeft(left: Version, version: Version, right: Version): Boolean {
            return left < version && version <= right
        }
    }

    override fun compareTo(other: Version): Int {
        if (x.compareTo(other.x) != 0) {
            return x.compareTo(other.x)
        }
        if (y.compareTo(other.y) != 0) {
            return y.compareTo(other.y)
        }
        if (z.compareTo(other.z) != 0) {
            return z.compareTo(other.z)
        }
        return 0
    }

    override fun toString(): String {
        return "$x.$y.$z"
    }
}

interface ExternalProcessHelper {
    fun executeProcessAndRedirectOutput(systemOut: OutputStream, systemErr: OutputStream, vararg args: String)

    fun executeProcess(vararg args: String) {
        executeProcessAndRedirectOutput(System.out, System.err, *args)
    }
}

open class ProcessBuilderExternalProcessHelper : ExternalProcessHelper {

    override fun executeProcessAndRedirectOutput(
        systemOut: OutputStream,
        systemErr: OutputStream,
        vararg args: String
    ) {
        val process = ProcessBuilder(args.toList()).inheritIO()
            .redirectOutput(Redirect.PIPE)
            .redirectError(Redirect.PIPE)
            .start()
        val threadsListToFinish = consumeProcessOutput(process, systemOut, systemErr)
        val exitCode = process.waitFor()
        threadsListToFinish.forEach { it.join() }
        if (exitCode != 0) {
            throw RuntimeException("Unexpected exit code: ${process.exitValue()} while executing: ${
                args.joinToString(separator = " ") { it }
            }")
        }
    }

    private fun consumeProcessOutput(p: Process, systemOut: OutputStream, systemErr: OutputStream): List<Thread> {
        return listOf(inheritIO(p.inputStream, PrintStream(systemOut)),
            inheritIO(p.errorStream, PrintStream(systemErr)))
    }

    private fun inheritIO(src: InputStream, dest: PrintStream): Thread {
        return Thread {
            val sc = Scanner(src)
            while (sc.hasNextLine()) {
                dest.println(sc.nextLine())
            }
        }.apply { start() }
    }
}

// The separate executor class is needed so we can call it in tests to simulate script execution
class ScriptExecutor(private vararg val args: String) : Callable<Int> {
    override fun call(): Int {
        return CommandLine(DeployDockerCompose())
            .apply { colorScheme = Help.defaultColorScheme(OFF) }
            .execute(*args)
    }
}

exitProcess(ScriptExecutor(*args).call())

