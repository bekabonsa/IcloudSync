package dev.bex.icloudsync.icloud

import dev.bex.icloudsync.data.model.AccountSecrets
import dev.bex.icloudsync.data.model.CookieData
import dev.bex.icloudsync.data.model.MediaKind
import dev.bex.icloudsync.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnofficialICloudGateway @Inject constructor(
    private val secretStore: SecretStore,
    private val json: Json,
) : ICloudGateway {
    private var authEndpoint = AUTH_ENDPOINT
    private var setupEndpoint = SETUP_ENDPOINT
    private var homeEndpoint = HOME_ENDPOINT

    internal constructor(
        secretStore: SecretStore,
        json: Json,
        endpoints: EndpointOverrides,
    ) : this(secretStore, json) {
        authEndpoint = endpoints.auth.trimEnd('/')
        setupEndpoint = endpoints.setup.trimEnd('/')
        homeEndpoint = endpoints.home.trimEnd('/')
    }

    private val cookies = PersistentCookieJar(secretStore.load()?.cookies.orEmpty())
    private val client = OkHttpClient.Builder()
        .cookieJar(cookies)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun isConfigured(): Boolean = secretStore.load() != null

    override suspend fun signIn(appleId: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        cookies.clear()
        val base = AccountSecrets(
            appleId = appleId.trim(),
            password = password,
            clientId = UUID.randomUUID().toString().lowercase(),
        )
        secretStore.save(base)
        try {
            performSrpLogin(base)
        } catch (error: Exception) {
            cookies.clear()
            secretStore.clear()
            throw error
        }
    }

    override suspend fun verifyTwoFactor(code: String): AuthResult = withContext(Dispatchers.IO) {
        val current = requireSecrets()
        val body = buildJsonObject {
            putJsonObject("securityCode") { put("code", code.trim()) }
        }
        executeJson(
            Request.Builder()
                .url("$authEndpoint/verify/trusteddevice/securitycode")
                .headers(authHeaders(current))
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            "2FA verification",
            setOf(200, 204),
            allowEmpty = true,
        )
        executeJson(
            Request.Builder()
                .url("$authEndpoint/2sv/trust")
                .headers(authHeaders(requireSecrets()))
                .get()
                .build(),
            "trusted session",
            setOf(200, 204),
            allowEmpty = true,
        )
        accountLogin(requireSecrets())
    }

    override suspend fun requestTwoFactorCode(): Boolean = withContext(Dispatchers.IO) {
        val current = requireSecrets()
        if (!current.requiresTwoFactor) return@withContext false
        requestTrustedDevicePrompt()
    }

    private fun requestTrustedDevicePrompt(): Boolean {
        runCatching {
            val current = requireSecrets()
            executeRaw(
                Request.Builder()
                    .url(authEndpoint)
                    .headers(authHeaders(current).newBuilder().set("Accept", "text/html").build())
                    .get()
                    .build(),
                "2FA options bootstrap",
                setOf(200),
            ).close()
        }
        return runCatching {
            executeRaw(
                Request.Builder()
                    .url("$authEndpoint/verify/trusteddevice")
                    .headers(authHeaders(requireSecrets()).newBuilder().set("Accept", "application/json").build())
                    .get()
                    .build(),
                "2FA trusted-device code request",
                setOf(200, 204),
            ).close()
            true
        }.getOrDefault(false)
    }

    override suspend fun validateOrRefresh(): AuthResult = withContext(Dispatchers.IO) {
        val current = requireSecrets()
        if (current.requiresTwoFactor) return@withContext AuthResult.RequiresTwoFactor
        try {
            executeJson(
                Request.Builder()
                    .url(setupUrl("validate", current))
                    .headers(commonHeaders())
                    .post("null".toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
                "session validation",
            )
            AuthResult.Authenticated
        } catch (_: ICloudException.Authentication) {
            performSrpLogin(current)
        }
    }

    override suspend fun storageUsage(): ICloudStorageUsage = withContext(Dispatchers.IO) {
        val secrets = requireSecrets()
        val url = setupUrl("storageUsageInfo", secrets).newBuilder()
            .addQueryParameter("dsid", secrets.dsid)
            .build()
        val response = executeJson(
            Request.Builder()
                .url(url)
                .headers(commonHeaders())
                .post("null".toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            "iCloud storage lookup",
        ).jsonObject
        parseStorageUsage(response)
    }

    override suspend fun countPhotos(includeHidden: Boolean): Long = withContext(Dispatchers.IO) {
        val index = if (includeHidden) HIDDEN_COUNT_INDEX else LIBRARY_COUNT_INDEX
        val payload = buildJsonObject {
            putJsonArray("batch") {
                addJsonObject {
                    put("resultsLimit", 1)
                    putJsonObject("query") {
                        put("recordType", "HyperionIndexCountLookup")
                        putJsonObject("filterBy") {
                            put("fieldName", "indexCountID")
                            put("comparator", "IN")
                            putJsonObject("fieldValue") {
                                put("type", "STRING_LIST")
                                putJsonArray("value") { add(index) }
                            }
                        }
                    }
                    put("zoneWide", true)
                    putJsonObject("zoneID") { put("zoneName", PRIMARY_ZONE) }
                }
            }
        }
        val response = cloudKitPost("internal/records/query/batch", payload)
        response["batch"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("records")?.jsonArray?.firstOrNull()?.jsonObject
            ?.fieldLong("itemCount")
            ?: throw ICloudException.Protocol("Photos count response is missing the item count")
    }

    override suspend fun listPhotos(continuation: String?, hidden: Boolean): RemotePage =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                putJsonObject("query") {
                    put("recordType", if (hidden) HIDDEN_LIST_QUERY else LIBRARY_LIST_QUERY)
                    putJsonArray("filterBy") {
                        addJsonObject {
                            put("fieldName", "startRank")
                            put("comparator", "EQUALS")
                            putJsonObject("fieldValue") { put("type", "INT64"); put("value", 0) }
                        }
                        addJsonObject {
                            put("fieldName", "direction")
                            put("comparator", "EQUALS")
                            putJsonObject("fieldValue") { put("type", "STRING"); put("value", "ASCENDING") }
                        }
                    }
                }
                putJsonObject("zoneID") { put("zoneName", PRIMARY_ZONE) }
                put("resultsLimit", 200)
                continuation?.let { put("continuationMarker", it) }
                putJsonArray("desiredKeys") { DESIRED_KEYS.forEach(::add) }
            }
            parseRemotePage(cloudKitPost("records/query", payload), hidden)
        }

    override suspend fun listChanges(syncToken: String): RemotePage = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("zones") {
                addJsonObject {
                    putJsonObject("zoneID") { put("zoneName", PRIMARY_ZONE) }
                    put("syncToken", syncToken)
                    putJsonArray("desiredKeys") { DESIRED_KEYS.forEach(::add) }
                    putJsonArray("desiredRecordTypes") { add("CPLMaster"); add("CPLAsset") }
                }
            }
            put("resultsLimit", 200)
        }
        val envelope = cloudKitPost("changes/zone", payload)
        val zone = envelope["zones"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ICloudException.Protocol("Photos changes response is missing the PrimarySync zone")
        parseRemotePage(zone, false)
    }

    override suspend fun streamRemoteOriginal(asset: RemoteAsset): InputStream = withContext(Dispatchers.IO) {
        val url = asset.downloadUrl ?: throw ICloudException.Protocol("Remote original has no download URL")
        executeStream(Request.Builder().url(url).get().build(), "remote media download")
    }

    override suspend fun uploadToPhotos(
        filename: String,
        sizeBytes: Long,
        source: () -> InputStream,
    ): UploadResult = withContext(Dispatchers.IO) {
        val secrets = requireSecrets()
        val uploadRoot = secrets.webservices["uploadimagews"]
            ?: secrets.webservices["photosupload"]
            ?: throw ICloudException.Protocol("Photos upload service is unavailable")
        val url = "$uploadRoot/upload".toHttpUrl().newBuilder()
            .addQueryParameter("dsid", secrets.dsid)
            .addQueryParameter("filename", filename)
            .build()
        val response = executeJson(
            Request.Builder().url(url).headers(commonHeaders()).post(StreamRequestBody(sizeBytes, source)).build(),
            "Photos upload",
        ).jsonObject
        response["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.let { error ->
            throw mapAppleError(error["code"]?.jsonPrimitive?.contentOrNull, error["message"]?.jsonPrimitive?.contentOrNull)
        }
        val records = response["records"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        val master = records.firstOrNull { it["recordType"]?.jsonPrimitive?.contentOrNull == "CPLMaster" }
        val assetRecord = records.firstOrNull { it["recordType"]?.jsonPrimitive?.contentOrNull == "CPLAsset" }
        val masterId = master?.get("recordName")?.jsonPrimitive?.contentOrNull
            ?: throw ICloudException.Protocol("Photos upload did not return a master record")
        UploadResult(
            masterId = masterId,
            assetId = assetRecord?.get("recordName")?.jsonPrimitive?.contentOrNull,
            duplicate = response["isDuplicate"]?.jsonPrimitive?.booleanOrNull
                ?: records.any { it["isDuplicate"]?.jsonPrimitive?.booleanOrNull == true },
        )
    }

    override suspend fun listFallbackDriveItems(): List<DriveItem> = withContext(Dispatchers.IO) {
        val folder = findFolderPath(listOf("IcloudSync", "Unsupported"), create = false)
            ?: return@withContext emptyList()
        val result = mutableListOf<DriveItem>()
        walkDrive(folder, "/IcloudSync/Unsupported", result)
        result
    }

    override suspend fun uploadToDrive(
        path: String,
        sizeBytes: Long,
        source: () -> InputStream,
    ): DriveItem = withContext(Dispatchers.IO) {
        val parts = path.trim('/').split('/').filter(String::isNotBlank)
        require(parts.size >= 2) { "Drive path must contain a folder and filename" }
        val filename = parts.last()
        val folder = findFolderPath(parts.dropLast(1), create = true)
            ?: throw ICloudException.Protocol("Could not create Drive fallback folder")
        val secrets = requireSecrets()
        val docRoot = secrets.webservices["docws"]
            ?: throw ICloudException.Protocol("iCloud Drive document service is unavailable")
        val token = cookies.snapshot().firstOrNull { it.name == "X-APPLE-WEBAUTH-VALIDATE" }
            ?.value?.substringAfter("t=", "")?.substringBefore(':')
            ?.takeIf(String::isNotBlank)
            ?: throw ICloudException.Authentication("Drive validation token is unavailable")
        val initPayload = buildJsonObject {
            put("filename", filename)
            put("type", "FILE")
            put("content_type", "")
            put("size", sizeBytes)
        }
        val initUrl = driveUrl(docRoot, "ws/com.apple.CloudDocs/upload/web").newBuilder()
            .addQueryParameter("token", token).build()
        val init = executeJson(
            Request.Builder().url(initUrl).headers(commonHeaders()).post(initPayload.toRequestBody(TEXT_MEDIA_TYPE)).build(),
            "Drive upload initialization",
        ).jsonArray.firstOrNull()?.jsonObject
            ?: throw ICloudException.Protocol("Drive upload initialization returned no target")
        val documentId = init["document_id"]?.jsonPrimitive?.contentOrNull
            ?: throw ICloudException.Protocol("Drive upload document ID is missing")
        val contentUrl = init["url"]?.jsonPrimitive?.contentOrNull
            ?: throw ICloudException.Protocol("Drive upload URL is missing")
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(filename, filename, StreamRequestBody(sizeBytes, source))
            .build()
        val content = executeJson(
            Request.Builder().url(contentUrl).post(multipart).build(),
            "Drive content upload",
        ).jsonObject["singleFile"]?.jsonObject
            ?: throw ICloudException.Protocol("Drive content receipt is missing")
        val data = buildJsonObject {
            putJsonObject("data") {
                put("signature", content.string("fileChecksum"))
                put("wrapping_key", content.string("wrappingKey"))
                put("reference_signature", content.string("referenceChecksum"))
                put("size", content["size"]?.jsonPrimitive?.longOrNull ?: sizeBytes)
                content["receipt"]?.jsonPrimitive?.contentOrNull?.let { put("receipt", it) }
            }
            put("command", "add_file")
            put("create_short_guid", true)
            put("document_id", documentId)
            putJsonObject("path") {
                put("starting_document_id", folder.id)
                put("path", filename)
            }
            put("allow_conflict", true)
            putJsonObject("file_flags") {
                put("is_writable", true); put("is_executable", false); put("is_hidden", false)
            }
            put("mtime", System.currentTimeMillis())
            put("btime", System.currentTimeMillis())
        }
        executeJson(
            Request.Builder()
                .url(driveUrl(docRoot, "ws/com.apple.CloudDocs/update/documents"))
                .headers(commonHeaders())
                .post(data.toRequestBody(TEXT_MEDIA_TYPE))
                .build(),
            "Drive metadata update",
        )
        DriveItem(documentId, path, sizeBytes, null)
    }

    override suspend fun streamDriveItem(item: DriveItem): InputStream = withContext(Dispatchers.IO) {
        val docRoot = requireSecrets().webservices["docws"]
            ?: throw ICloudException.Protocol("iCloud Drive document service is unavailable")
        val ticket = executeJson(
            Request.Builder()
                .url(driveUrl(docRoot, "ws/com.apple.CloudDocs/download/by_id").newBuilder()
                    .addQueryParameter("document_id", item.driveId).build())
                .get().build(),
            "Drive download ticket",
        ).jsonObject
        val url = ticket["data_token"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?: ticket["package_token"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?: throw ICloudException.Protocol("Drive download URL is missing")
        executeStream(Request.Builder().url(url).get().build(), "Drive download")
    }

    override fun logout() {
        cookies.clear()
        secretStore.clear()
    }

    private suspend fun performSrpLogin(initial: AccountSecrets): AuthResult {
        val srp = AppleSrp(initial.appleId)
        val headers = authHeaders(initial)
        val authorizeUrl = "$authEndpoint/authorize/signin".toHttpUrl().newBuilder()
            .addQueryParameter("frame_id", initial.clientId)
            .addQueryParameter("skVersion", "7")
            .addQueryParameter("iframeid", initial.clientId)
            .addQueryParameter("client_id", WIDGET_KEY)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", homeEndpoint)
            .addQueryParameter("response_mode", "web_message")
            .addQueryParameter("state", initial.clientId)
            .addQueryParameter("authVersion", "latest")
            .build()
        executeRaw(Request.Builder().url(authorizeUrl).headers(headers).get().build(), "SRP bootstrap", setOf(200)).close()
        val initBody = buildJsonObject {
            put("a", srp.publicA())
            put("accountName", initial.appleId)
            putJsonArray("protocols") { add("s2k"); add("s2k_fo") }
        }
        val challenge = executeJson(
            Request.Builder().url("$authEndpoint/signin/init").headers(headers)
                .post(initBody.toRequestBody(JSON_MEDIA_TYPE)).build(),
            "SRP initialization",
        ).jsonObject
        val proof = srp.complete(
            password = initial.password,
            saltBase64 = challenge.string("salt"),
            serverBBase64 = challenge.string("b"),
            iterations = challenge["iteration"]?.jsonPrimitive?.intOrNull
                ?: throw ICloudException.Protocol("SRP iteration count is missing"),
            protocol = challenge.string("protocol"),
        )
        val completeBody = buildJsonObject {
            put("accountName", initial.appleId)
            put("c", challenge.string("c"))
            put("m1", proof.m1)
            put("m2", proof.m2)
            put("rememberMe", true)
            putJsonArray("trustTokens") {
                initial.trustToken.takeIf(String::isNotBlank)?.let(::add)
            }
        }
        val completion = executeRaw(
            Request.Builder()
                .url("$authEndpoint/signin/complete?isRememberMeEnabled=true")
                .headers(authHeaders(requireSecrets()))
                .post(completeBody.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            "SRP completion",
            setOf(200, 409),
        )
        val mfaRequired = completion.code == 409
        completion.close()
        val promptRequested = if (mfaRequired) requestTrustedDevicePrompt() else false
        val result = accountLogin(requireSecrets())
        if (result == AuthResult.RequiresTwoFactor && !promptRequested) requestTrustedDevicePrompt()
        return result
    }

    private fun accountLogin(current: AccountSecrets): AuthResult {
        val refreshed = requireSecrets()
        if (refreshed.sessionToken.isBlank()) {
            throw ICloudException.Authentication("Apple did not issue a session token")
        }
        val body = buildJsonObject {
            put("accountCountryCode", refreshed.accountCountry)
            put("dsWebAuthToken", refreshed.sessionToken)
            put("extended_login", true)
            put("trustToken", refreshed.trustToken)
        }
        val response = executeJson(
            Request.Builder().url(setupUrl("accountLogin", refreshed)).headers(commonHeaders())
                .post(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
            "iCloud account login",
        ).jsonObject
        val dsid = response["dsInfo"]?.jsonObject?.get("dsid")?.jsonPrimitive?.contentOrNull.orEmpty()
        val services = response["webservices"]?.jsonObject.orEmpty().mapNotNull { (name, value) ->
            value.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.let { name to it }
        }.toMap()
        if (dsid.isBlank() || services.isEmpty()) {
            throw ICloudException.Protocol("iCloud account login returned incomplete service discovery")
        }
        val trusted = response["hsaTrustedBrowser"]?.jsonPrimitive?.booleanOrNull ?: false
        val challenge = response["hsaChallengeRequired"]?.jsonPrimitive?.booleanOrNull ?: false
        val requiresTwoFactor = !trusted || challenge
        val finalSecrets = requireSecrets().copy(
            dsid = dsid,
            webservices = services,
            requiresTwoFactor = requiresTwoFactor,
            cookies = cookies.snapshot(),
        )
        secretStore.save(finalSecrets)
        return if (requiresTwoFactor) AuthResult.RequiresTwoFactor else AuthResult.Authenticated
    }

    private fun cloudKitPost(path: String, body: JsonObject): JsonObject {
        val secrets = requireSecrets()
        val root = secrets.webservices["ckdatabasews"]
            ?: secrets.webservices["photos"]
            ?: throw ICloudException.Protocol("Photos CloudKit service is unavailable")
        val url = "$root/database/1/com.apple.photos.cloud/production/private/$path".toHttpUrl().newBuilder()
            .addQueryParameter("clientBuildNumber", CLIENT_BUILD)
            .addQueryParameter("clientMasteringNumber", CLIENT_MASTERING)
            .addQueryParameter("clientId", secrets.clientId)
            .addQueryParameter("dsid", secrets.dsid)
            .addQueryParameter("remapEnums", "true")
            .addQueryParameter("getCurrentSyncToken", "true")
            .build()
        return executeJson(
            Request.Builder().url(url).headers(commonHeaders()).post(body.toRequestBody(TEXT_MEDIA_TYPE)).build(),
            "Photos library request",
        ).jsonObject
    }

    private fun parseRemotePage(root: JsonObject, hidden: Boolean): RemotePage {
        val recordArray = root["records"]
            ?: throw ICloudException.Protocol("Photos response is missing records")
        val records = runCatching { recordArray.jsonArray }
            .getOrElse { throw ICloudException.Protocol("Photos records have an unexpected shape", it) }
            .mapNotNull { it as? JsonObject }
        val assetsByMaster = records.filter { it["recordType"]?.jsonPrimitive?.contentOrNull == "CPLAsset" }
            .mapNotNull { record -> record.reference("masterRef")?.let { it to record } }
            .toMap()
        val masters = records.filter { it["recordType"]?.jsonPrimitive?.contentOrNull == "CPLMaster" }
        val deletionReferences = records.filter { record ->
            val type = record["recordType"]?.jsonPrimitive?.contentOrNull
            type != "CPLMaster" && (
                record["deleted"]?.jsonPrimitive?.booleanOrNull == true ||
                    record.fieldLong("isDeleted") == 1L
                )
        }.mapNotNull { record ->
            record["recordName"]?.jsonPrimitive?.contentOrNull?.let { recordName ->
                RemoteAsset(
                    masterId = recordName,
                    assetId = null,
                    filename = recordName,
                    mediaKind = MediaKind.IMAGE,
                    sizeBytes = 0,
                    capturedAtEpochMs = 0,
                    providerChecksum = null,
                    downloadUrl = null,
                    hidden = false,
                    deleted = true,
                )
            }
        }
        return RemotePage(
            assets = masters.map { master ->
                val masterId = master.string("recordName")
                val asset = assetsByMaster[masterId]
                val type = master.fieldString("itemType").orEmpty()
                val encodedName = master.fieldString("filenameEnc").orEmpty()
                val filename = runCatching { Base64.getDecoder().decode(encodedName).decodeToString() }
                    .getOrNull()?.takeIf { it.isNotBlank() && !it.contains('\u0000') } ?: encodedName
                val resource = master.fieldObject("resOriginalRes")
                RemoteAsset(
                    masterId = masterId,
                    assetId = asset?.get("recordName")?.jsonPrimitive?.contentOrNull,
                    filename = filename.ifBlank { masterId },
                    mediaKind = if (type.contains("movie", true) || type.contains("video", true)) MediaKind.VIDEO else MediaKind.IMAGE,
                    sizeBytes = resource?.get("size")?.jsonPrimitive?.longOrNull ?: 0,
                    capturedAtEpochMs = asset?.fieldLong("addedDate") ?: 0,
                    providerChecksum = resource?.get("fileChecksum")?.jsonPrimitive?.contentOrNull,
                    downloadUrl = resource?.get("downloadURL")?.jsonPrimitive?.contentOrNull,
                    hidden = hidden || (asset?.fieldLong("isHidden") == 1L),
                    deleted = master.fieldLong("isDeleted") == 1L || asset?.fieldLong("isDeleted") == 1L,
                )
            } + deletionReferences,
            continuationMarker = root["continuationMarker"]?.jsonPrimitive?.contentOrNull,
            syncToken = root["syncToken"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private data class DriveNode(val id: String, val name: String, val type: String, val size: Long, val zone: String)

    private fun findFolderPath(parts: List<String>, create: Boolean): DriveNode? {
        var current = DriveNode(ROOT_FOLDER, "", "folder", 0, CLOUD_DOCS_ZONE)
        for (part in parts) {
            var child = driveChildren(current.id).firstOrNull { it.type == "folder" && it.name == part }
            if (child == null && create) {
                createFolder(current.id, part)
                child = driveChildren(current.id).firstOrNull { it.type == "folder" && it.name == part }
            }
            current = child ?: return null
        }
        return current
    }

    private fun walkDrive(folder: DriveNode, path: String, output: MutableList<DriveItem>) {
        driveChildren(folder.id).forEach { node ->
            if (node.type == "folder") walkDrive(node, "$path/${node.name}", output)
            else output += DriveItem(node.id, "$path/${node.name}", node.size, null)
        }
    }

    private fun driveChildren(folderId: String): List<DriveNode> {
        val root = requireSecrets().webservices["drivews"]
            ?: throw ICloudException.Protocol("iCloud Drive service is unavailable")
        val payload = buildJsonArray { addJsonObject { put("drivewsid", folderId); put("partialData", false) } }
        val result = executeJson(
            Request.Builder().url(driveUrl(root, "retrieveItemDetailsInFolders"))
                .headers(commonHeaders()).post(payload.toRequestBody(JSON_MEDIA_TYPE)).build(),
            "Drive folder listing",
        ).jsonArray.firstOrNull()?.jsonObject ?: return emptyList()
        return result["items"]?.jsonArray.orEmpty().mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["drivewsid"]?.jsonPrimitive?.contentOrNull ?: obj["docwsid"]?.jsonPrimitive?.contentOrNull
            id?.let {
                DriveNode(
                    id = it,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "file",
                    size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0,
                    zone = obj["zone"]?.jsonPrimitive?.contentOrNull ?: CLOUD_DOCS_ZONE,
                )
            }
        }
    }

    private fun createFolder(parentId: String, name: String) {
        val root = requireSecrets().webservices["drivews"]
            ?: throw ICloudException.Protocol("iCloud Drive service is unavailable")
        val payload = buildJsonObject {
            put("destinationDrivewsId", parentId)
            putJsonArray("folders") {
                addJsonObject {
                    put("clientId", "FOLDER::UNKNOWN_ZONE::TempId-${UUID.randomUUID()}")
                    put("name", name)
                }
            }
        }
        executeJson(
            Request.Builder().url(driveUrl(root, "createFolders")).headers(commonHeaders())
                .post(payload.toRequestBody(TEXT_MEDIA_TYPE)).build(),
            "Drive folder creation",
        )
    }

    private fun driveUrl(root: String, path: String): HttpUrl {
        val secrets = requireSecrets()
        return "${root.trimEnd('/')}/${path.trimStart('/')}".toHttpUrl().newBuilder()
            .addQueryParameter("clientBuildNumber", CLIENT_BUILD)
            .addQueryParameter("clientMasteringNumber", CLIENT_MASTERING)
            .addQueryParameter("clientId", secrets.clientId)
            .addQueryParameter("dsid", secrets.dsid)
            .build()
    }

    private fun executeJson(
        request: Request,
        operation: String,
        accepted: Set<Int> = setOf(200),
        allowEmpty: Boolean = false,
    ): JsonElement {
        executeRaw(request, operation, accepted).use { response ->
            val body = response.body?.string().orEmpty()
            if (body.isBlank() && allowEmpty) return JsonObject(emptyMap())
            return runCatching { json.parseToJsonElement(body) }
                .getOrElse { throw ICloudException.Protocol("$operation returned an unreadable response", it) }
        }
    }

    private fun executeRaw(request: Request, operation: String, accepted: Set<Int>): Response {
        val response = try {
            client.newCall(request).execute()
        } catch (error: Exception) {
            throw ICloudException.Transient("$operation could not reach Apple", error)
        }
        captureHeaders(response)
        if (response.code !in accepted) {
            val retry = response.header("Retry-After")?.toLongOrNull()
            val code = response.code
            response.close()
            when {
                code == 401 || code == 403 || code == 421 -> throw ICloudException.Authentication("$operation needs a new login")
                code == 429 -> throw ICloudException.RateLimited(retry)
                code >= 500 -> throw ICloudException.Transient("$operation failed temporarily")
                code in 400..499 -> throw ICloudException.Permanent("$operation was rejected with HTTP $code")
                else -> throw ICloudException.Protocol("$operation returned unexpected HTTP $code")
            }
        }
        return response
    }

    private fun executeStream(request: Request, operation: String): InputStream {
        val response = executeRaw(request, operation, setOf(200))
        return response.body?.byteStream() ?: run { response.close(); throw ICloudException.Protocol("$operation returned no data") }
    }

    private fun captureHeaders(response: Response) {
        val existing = secretStore.load() ?: return
        val updated = existing.copy(
            accountCountry = response.header("X-Apple-ID-Account-Country") ?: existing.accountCountry,
            sessionId = response.header("X-Apple-ID-Session-Id") ?: existing.sessionId,
            sessionToken = response.header("X-Apple-Session-Token") ?: existing.sessionToken,
            trustToken = response.header("X-Apple-TwoSV-Trust-Token") ?: existing.trustToken,
            scnt = response.header("scnt") ?: existing.scnt,
            authAttributes = response.header("X-Apple-Auth-Attributes") ?: existing.authAttributes,
            cookies = cookies.snapshot(),
        )
        secretStore.save(updated)
    }

    private fun authHeaders(secrets: AccountSecrets): Headers = Headers.Builder()
        .add("Accept", "application/json, text/javascript")
        .add("Content-Type", "application/json")
        .add("X-Apple-OAuth-Client-Id", WIDGET_KEY)
        .add("X-Apple-OAuth-Client-Type", "firstPartyAuth")
        .add("X-Apple-OAuth-Redirect-URI", homeEndpoint)
        .add("X-Apple-OAuth-Require-Grant-Code", "true")
        .add("X-Apple-OAuth-Response-Mode", "web_message")
        .add("X-Apple-OAuth-Response-Type", "code")
        .add("X-Apple-OAuth-State", secrets.clientId)
        .add("X-Apple-Frame-Id", secrets.clientId)
        .add("X-Apple-Widget-Key", WIDGET_KEY)
        .add("X-Apple-FD-Client-Info", FD_CLIENT_INFO)
        .add("Origin", homeEndpoint)
        .add("Referer", "https://idmsa.apple.com/")
        .add("User-Agent", USER_AGENT)
        .apply {
            secrets.scnt.takeIf(String::isNotBlank)?.let { add("scnt", it) }
            secrets.sessionId.takeIf(String::isNotBlank)?.let { add("X-Apple-ID-Session-Id", it) }
            secrets.authAttributes.takeIf(String::isNotBlank)?.let { add("X-Apple-Auth-Attributes", it) }
        }.build()

    private fun commonHeaders(): Headers = Headers.Builder()
        .add("Origin", homeEndpoint)
        .add("Referer", "$homeEndpoint/")
        .add("User-Agent", USER_AGENT)
        .build()

    private fun setupUrl(path: String, secrets: AccountSecrets): HttpUrl =
        "$setupEndpoint/${path.trimStart('/')}".toHttpUrl().newBuilder()
            .addQueryParameter("clientBuildNumber", CLIENT_BUILD)
            .addQueryParameter("clientMasteringNumber", CLIENT_MASTERING)
            .addQueryParameter("clientId", secrets.clientId)
            .build()

    private fun requireSecrets(): AccountSecrets = secretStore.load()
        ?: throw ICloudException.Authentication("No Apple account is configured")

    private fun mapAppleError(code: String?, message: String?): ICloudException = when (code) {
        "TYPE_UNSUPPORTED" -> ICloudException.UnsupportedType()
        "QUOTA_EXCEEDED", "ZONE_QUOTA_EXCEEDED" -> ICloudException.QuotaExceeded()
        "AUTHENTICATION_REQUIRED", "NOT_AUTHENTICATED" -> ICloudException.Authentication(message ?: "Login required")
        else -> ICloudException.Protocol("Apple upload error: ${code ?: "UNKNOWN"}${message?.let { ": $it" }.orEmpty()}")
    }

    internal fun parseStorageUsage(response: JsonObject): ICloudStorageUsage {
        val usage = response["storageUsageInfo"]?.jsonObject
            ?: throw ICloudException.Protocol("iCloud storage response is missing usage information")
        val quota = response["quotaStatus"]?.jsonObject ?: JsonObject(emptyMap())
        val total = usage["totalStorageInBytes"]?.jsonPrimitive?.longOrNull
            ?: throw ICloudException.Protocol("iCloud storage response is missing the total")
        val used = usage["usedStorageInBytes"]?.jsonPrimitive?.longOrNull
            ?: throw ICloudException.Protocol("iCloud storage response is missing the amount used")
        if (total <= 0 || used < 0) {
            throw ICloudException.Protocol("iCloud storage response contains invalid totals")
        }
        val categories = response["storageUsageByMedia"]?.jsonArray.orEmpty().mapNotNull { item ->
            val value = item as? JsonObject ?: return@mapNotNull null
            val key = value["mediaKey"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val bytes = value["usageInBytes"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            ICloudStorageCategory(
                key = key,
                label = value["displayLabel"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: key,
                usageBytes = bytes.coerceAtLeast(0),
                displayColor = value["displayColor"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return ICloudStorageUsage(
            totalBytes = total,
            usedBytes = used.coerceAtMost(total),
            availableBytes = (total - used).coerceAtLeast(0),
            overQuota = quota["overQuota"]?.jsonPrimitive?.booleanOrNull == true || used >= total,
            almostFull = quota["almost-full"]?.jsonPrimitive?.booleanOrNull == true,
            categories = categories,
        )
    }

    private class StreamRequestBody(
        private val length: Long,
        private val opener: () -> InputStream,
    ) : RequestBody() {
        override fun contentType(): MediaType = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) { opener().use { sink.writeAll(it.source()) } }
    }

    private class PersistentCookieJar(initial: List<CookieData>) : CookieJar {
        private val values = initial.mapNotNull(::fromData).associateBy(::key).toMutableMap()

        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { values[key(it)] = it }
            values.values.removeAll { it.expiresAt < System.currentTimeMillis() }
        }

        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            values.values.filter { it.matches(url) && it.expiresAt >= System.currentTimeMillis() }

        @Synchronized fun snapshot(): List<CookieData> = values.values.map {
            CookieData(it.name, it.value, it.domain, it.path, it.expiresAt, it.secure, it.httpOnly, it.hostOnly)
        }

        @Synchronized fun clear() = values.clear()

        companion object {
            private fun key(cookie: Cookie) = "${cookie.name}|${cookie.domain}|${cookie.path}"
            private fun fromData(data: CookieData): Cookie? = runCatching {
                Cookie.Builder().name(data.name).value(data.value).path(data.path).expiresAt(data.expiresAt)
                    .apply {
                        if (data.hostOnly) hostOnlyDomain(data.domain) else domain(data.domain)
                        if (data.secure) secure()
                        if (data.httpOnly) httpOnly()
                    }.build()
            }.getOrNull()
        }
    }

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull
        ?: throw ICloudException.Protocol("Apple response is missing $key")
    private fun JsonObject.fieldValue(key: String): JsonElement? = this["fields"]?.jsonObject?.get(key)?.jsonObject?.get("value")
    private fun JsonObject.fieldString(key: String): String? = fieldValue(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.fieldLong(key: String): Long? = fieldValue(key)?.jsonPrimitive?.longOrNull
    private fun JsonObject.fieldObject(key: String): JsonObject? = fieldValue(key) as? JsonObject
    private fun JsonObject.reference(key: String): String? = fieldObject(key)?.get("recordName")?.jsonPrimitive?.contentOrNull
    private fun JsonElement.toRequestBody(contentType: MediaType): RequestBody =
        toString().toRequestBody(contentType)

    private companion object {
        const val HOME_ENDPOINT = "https://www.icloud.com"
        const val SETUP_ENDPOINT = "https://setup.icloud.com/setup/ws/1"
        const val AUTH_ENDPOINT = "https://idmsa.apple.com/appleauth/auth"
        const val WIDGET_KEY = "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
        const val FD_CLIENT_INFO = "{\"U\":\"$USER_AGENT\",\"L\":\"en-US\",\"Z\":\"GMT+00:00\",\"V\":\"1.1\",\"F\":\"\"}"
        const val CLIENT_BUILD = "2534Project66"
        const val CLIENT_MASTERING = "2534B22"
        const val PRIMARY_ZONE = "PrimarySync"
        const val LIBRARY_LIST_QUERY = "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted"
        const val HIDDEN_LIST_QUERY = "CPLAssetAndMasterHiddenByAssetDate"
        const val LIBRARY_COUNT_INDEX = "CPLAssetByAssetDateWithoutHiddenOrDeleted"
        const val HIDDEN_COUNT_INDEX = "CPLAssetHiddenByAssetDate"
        const val CLOUD_DOCS_ZONE = "com.apple.CloudDocs"
        const val ROOT_FOLDER = "FOLDER::com.apple.CloudDocs::root"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val TEXT_MEDIA_TYPE = "text/plain".toMediaType()
        val DESIRED_KEYS = listOf(
            "filenameEnc", "itemType", "addedDate", "masterRef", "isDeleted", "isHidden",
            "resOriginalRes", "resOriginalFingerprint", "resOriginalFileType", "resOriginalWidth", "resOriginalHeight",
        )
    }

    internal data class EndpointOverrides(
        val auth: String,
        val setup: String,
        val home: String,
    )
}
