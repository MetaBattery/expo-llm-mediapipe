package expo.modules.llmmediapipe

import android.content.Context
import android.util.Log
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException

private const val TAG = "ExpoLlmMediapipe"
private const val DOWNLOAD_DIRECTORY = "llm_models"
private const val NO_MODEL_HANDLE = -1

internal fun parseContentLength(headerValue: String?): Long? {
  if (headerValue.isNullOrBlank()) {
    return null
  }

  val sanitized = headerValue.trim().replace("_", "")
  return sanitized.toLongOrNull()
}

internal suspend fun downloadWithTimeout(
  url: String,
  timeoutMillis: Long,
  headers: Map<String, Any>?,
  tempFile: File,
  isActive: () -> Boolean,
  onProgress: (bytesDownloaded: Long, totalBytes: Long, progress: Double) -> Unit
): Pair<Long, Long> {
  var connection: HttpURLConnection? = null

  try {
    connection = (URL(url).openConnection() as HttpURLConnection).apply {
      headers?.forEach { (key, value) ->
        setRequestProperty(key, value.toString())
      }

      val timeoutInt = timeoutMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
      connectTimeout = timeoutInt
      readTimeout = timeoutInt
    }

    if (tempFile.exists()) {
      tempFile.delete()
    }

    var bytesDownloaded = 0L
    var totalBytes = -1L
    var lastUpdateTime = System.currentTimeMillis()

    withTimeout(timeoutMillis) {
      connection.connect()
      val headerContentLength = parseContentLength(connection.getHeaderField("Content-Length"))
      val contentLengthLong = connection.contentLengthLong.takeIf { it >= 0 }
      val fallbackContentLength = connection.contentLength.takeIf { it >= 0 }?.toLong()
      totalBytes = headerContentLength
        ?: contentLengthLong
        ?: fallbackContentLength
        ?: -1L

      BufferedInputStream(connection.inputStream).use { input ->
        FileOutputStream(tempFile).use { output ->
          val buffer = ByteArray(8192)
          var count: Int

          while (input.read(buffer).also { count = it } != -1) {
            if (!isActive()) {
              throw CancellationException("Download cancelled")
            }

            val countLong = count.toLong()
            bytesDownloaded = if (Long.MAX_VALUE - bytesDownloaded < countLong) {
              Long.MAX_VALUE
            } else {
              bytesDownloaded + countLong
            }
            output.write(buffer, 0, count)

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUpdateTime > 100) {
              lastUpdateTime = currentTime
              val progress = if (totalBytes > 0) bytesDownloaded.toDouble() / totalBytes else 0.0
              val reportedTotalBytes = when {
                totalBytes > 0 -> totalBytes
                bytesDownloaded > 0 -> bytesDownloaded
                else -> 0L
              }
              onProgress(bytesDownloaded, reportedTotalBytes, progress)
            }
          }
        }
      }
    }

    return bytesDownloaded to totalBytes
  } finally {
    connection?.disconnect()
  }
}

class ExpoLlmMediapipeModule : Module() {
  private var nextHandle = 1
  private val modelMap = mutableMapOf<Int, LlmInferenceModel>()

  // Define these functions at class level, not in the definition block
  private fun createInferenceListener(modelHandle: Int): InferenceListener {
    return object : InferenceListener {
      override fun logging(model: LlmInferenceModel, message: String) {
        sendEvent("logging", mapOf(
          "handle" to modelHandle,
          "message" to message
        ))
      }
      
      override fun onError(model: LlmInferenceModel, requestId: Int, error: String) {
        sendEvent("onErrorResponse", mapOf(
          "handle" to modelHandle,
          "requestId" to requestId,
          "error" to error
        ))
      }
      
      override fun onResults(model: LlmInferenceModel, requestId: Int, response: String) {
        sendEvent("onPartialResponse", mapOf(
          "handle" to modelHandle,
          "requestId" to requestId,
          "response" to response
        ))
      }
    }
  }
  
  private fun copyFileToInternalStorageIfNeeded(modelName: String, context: Context): File {
    val outputFile = File(context.filesDir, modelName)

    // Check if the file already exists
    if (outputFile.exists()) {
      // The file already exists, no need to copy again
      sendEvent("logging", mapOf(
        "handle" to NO_MODEL_HANDLE,
        "message" to "File already exists: ${outputFile.path}, size: ${outputFile.length()}"
      ))
      return outputFile
    }

    try {
      sendEvent("logging", mapOf(
        "handle" to NO_MODEL_HANDLE,
        "message" to "Resolved asset path for $modelName"
      ))

      sendEvent("logging", mapOf(
        "handle" to NO_MODEL_HANDLE,
        "message" to "Copying asset $modelName to ${outputFile.path}"
      ))

      // File doesn't exist, proceed with copying
      val inputStream = try {
        context.assets.open(modelName)
      } catch (e: Exception) {
        sendEvent("logging", mapOf(
          "handle" to NO_MODEL_HANDLE,
          "message" to "Failed to open asset $modelName: ${e.message}"
        ))
        throw e
      }

      inputStream.use { input ->
        FileOutputStream(outputFile).use { outputStream ->
          val buffer = ByteArray(1024)
          var read: Int
          var total = 0

          while (input.read(buffer).also { read = it } != -1) {
            outputStream.write(buffer, 0, read)
            total += read

            if (total % (1024 * 1024) == 0) { // Log every MB
              sendEvent("logging", mapOf(
                "handle" to NO_MODEL_HANDLE,
                "message" to "Copied $total bytes so far"
              ))
            }
          }
          
          sendEvent("logging", mapOf(
            "handle" to NO_MODEL_HANDLE,
            "message" to "Copied $total bytes total"
          ))
        }
      }
    } catch (e: Exception) {
      sendEvent("logging", mapOf(
        "handle" to NO_MODEL_HANDLE,
        "message" to "Error copying file: ${e.message}"
      ))
      throw e
    }

    return outputFile
  }

  // Model directory management
  private fun getModelDirectory(): File {
    val modelDir = File(appContext.reactContext!!.filesDir, DOWNLOAD_DIRECTORY)
    if (!modelDir.exists()) {
      modelDir.mkdirs()
    }
    return modelDir
  }
  
  private fun getModelFile(modelName: String): File {
    return File(getModelDirectory(), modelName)
  }
  
  // Create model internal helper method
  private fun createModelInternal(modelPath: String, maxTokens: Int, topK: Int, temperature: Double, randomSeed: Int): Int {
    val modelHandle = nextHandle++
    val model = LlmInferenceModel(
      appContext.reactContext!!,
      modelPath,
      maxTokens,
      topK,
      temperature.toFloat(),
      randomSeed,
      inferenceListener = createInferenceListener(modelHandle)
    )
    modelMap[modelHandle] = model
    return modelHandle
  }

  // Track active downloads
  private val activeDownloads = mutableMapOf<String, Job>()

  override fun definition() = ModuleDefinition {
    Name("ExpoLlmMediapipe")

    Constants(
      "PI" to Math.PI
    )

    Events("onChange", "onPartialResponse", "onErrorResponse", "logging", "downloadProgress")

    Function("hello") {
      "Hello world from MediaPipe LLM! 👋"
    }

    AsyncFunction("createModel") { modelPath: String, maxTokens: Int, topK: Int, temperature: Double, randomSeed: Int, promise: Promise ->
      try {
        val modelHandle = nextHandle++
        
        // Log that we're creating a model
        sendEvent("logging", mapOf(
          "handle" to modelHandle,
          "message" to "Creating model from path: $modelPath"
        ))
        
        val model = LlmInferenceModel(
          appContext.reactContext!!,
          modelPath,
          maxTokens,
          topK,
          temperature.toFloat(),
          randomSeed,
          inferenceListener = createInferenceListener(modelHandle)
        )
        modelMap[modelHandle] = model
        promise.resolve(modelHandle)
      } catch (e: Exception) {
        // Log the error
        sendEvent("logging", mapOf(
          "handle" to NO_MODEL_HANDLE,
          "message" to "Model creation failed: ${e.message}"
        ))
        promise.reject("MODEL_CREATION_FAILED", e.message ?: "Unknown error", e)
      }
    }

    AsyncFunction("createModelFromAsset") { modelName: String, maxTokens: Int, topK: Int, temperature: Double, randomSeed: Int, promise: Promise ->
      try {
        // Log that we're creating a model from asset
        sendEvent("logging", mapOf(
          "handle" to NO_MODEL_HANDLE,
          "message" to "Creating model from asset: $modelName"
        ))
        
        val modelPath = copyFileToInternalStorageIfNeeded(modelName, appContext.reactContext!!).path
        
        sendEvent("logging", mapOf(
          "handle" to NO_MODEL_HANDLE,
          "message" to "Model file copied to: $modelPath"
        ))
        
        val modelHandle = nextHandle++
        val model = LlmInferenceModel(
          appContext.reactContext!!,
          modelPath,
          maxTokens,
          topK,
          temperature.toFloat(),
          randomSeed,
          inferenceListener = createInferenceListener(modelHandle)
        )
        modelMap[modelHandle] = model
        promise.resolve(modelHandle)
      } catch (e: Exception) {
        // Log the error
        sendEvent("logging", mapOf(
          "handle" to NO_MODEL_HANDLE,
          "message" to "Model creation from asset failed: ${e.message}"
        ))
        promise.reject("MODEL_CREATION_FAILED", e.message ?: "Unknown error", e)
      }
    }

    AsyncFunction("releaseModel") { handle: Int, promise: Promise ->
      try {
        val model = modelMap[handle]
        if (model == null) {
          promise.reject("INVALID_HANDLE", "No model found for handle $handle", null)
          return@AsyncFunction
        }

        try {
          model.close()
        } catch (e: Exception) {
          sendEvent("logging", mapOf(
            "handle" to handle,
            "message" to "Failed to close model: ${e.message}"
          ))
          promise.reject("RELEASE_FAILED", e.message ?: "Unknown error", e)
          return@AsyncFunction
        }

        modelMap.remove(handle)
        promise.resolve(true)
      } catch (e: Exception) {
        promise.reject("RELEASE_FAILED", e.message ?: "Unknown error", e)
      }
    }

    AsyncFunction("generateResponse") { handle: Int, requestId: Int, prompt: String, promise: Promise ->
      try {
        val model = modelMap[handle]
        if (model == null) {
          promise.reject("INVALID_HANDLE", "No model found for handle $handle", null)
          return@AsyncFunction
        }
        
        sendEvent("logging", mapOf(
          "handle" to handle,
          "message" to "Generating response with prompt: ${prompt.take(30)}..."
        ))
        
        // Use the synchronous version
        val response = model.generateResponse(requestId, prompt)
        promise.resolve(response)
      } catch (e: Exception) {
        sendEvent("logging", mapOf(
          "handle" to handle,
          "message" to "Generation error: ${e.message}"
        ))
        promise.reject("GENERATION_FAILED", e.message ?: "Unknown error", e)
      }
    }

    AsyncFunction("generateResponseAsync") { handle: Int, requestId: Int, prompt: String, promise: Promise ->
      try {
        val model = modelMap[handle]
        if (model == null) {
          promise.reject("INVALID_HANDLE", "No model found for handle $handle", null)
          return@AsyncFunction
        }
        
        sendEvent("logging", mapOf(
          "handle" to handle,
          "requestId" to requestId,
          "message" to "Starting async generation with prompt: ${prompt.take(30)}..."
        ))
        
        // Use the async version with callback and event emission
        try {
          model.generateResponseAsync(requestId, prompt) { result ->
            try {
              if (result.isEmpty()) {
                sendEvent("logging", mapOf(
                  "handle" to handle,
                  "requestId" to requestId,
                  "message" to "Generation completed but returned empty result"
                ))
                promise.reject("GENERATION_FAILED", "Failed to generate response", null)
              } else {
                sendEvent("logging", mapOf(
                  "handle" to handle,
                  "requestId" to requestId,
                  "message" to "Generation completed successfully with ${result.length} characters"
                ))
                
                // We don't resolve with the final result here anymore
                // The client will assemble the full response from streaming events
                promise.resolve(true)  // Just send success signal
              }
            } catch (e: Exception) {
              sendEvent("logging", mapOf(
                "handle" to handle,
                "requestId" to requestId,
                "message" to "Error in async result callback: ${e.message}"
              ))
              // Only reject if not already settled
              promise.reject("GENERATION_ERROR", e.message ?: "Unknown error", e)
            }
          }
        } catch (e: Exception) {
          sendEvent("logging", mapOf(
            "handle" to handle,
            "requestId" to requestId,
            "message" to "Exception during generateResponseAsync call: ${e.message}"
          ))
          promise.reject("GENERATION_ERROR", e.message ?: "Unknown error", e)
        }
      } catch (e: Exception) {
        sendEvent("logging", mapOf(
          "handle" to handle,
          "message" to "Outer exception in generateResponseAsync: ${e.message}"
        ))
        promise.reject("GENERATION_ERROR", e.message ?: "Unknown error", e)
      }
    }

    // Check if model is downloaded
    AsyncFunction("isModelDownloaded") { modelName: String, promise: Promise ->
      val modelFile = getModelFile(modelName)
      promise.resolve(modelFile.exists() && modelFile.length() > 0)
    }
    
    // Get list of downloaded models
    AsyncFunction("getDownloadedModels") { promise: Promise ->
      val models = getModelDirectory().listFiles()?.map { it.name } ?: emptyList()
      promise.resolve(models)
    }
    
    // Delete downloaded model
    AsyncFunction("deleteDownloadedModel") { modelName: String, promise: Promise ->
      val modelFile = getModelFile(modelName)
      val result = if (modelFile.exists()) modelFile.delete() else false
      promise.resolve(result)
    }
    
    // Download model from URL
    AsyncFunction("downloadModel") { url: String, modelName: String, options: Map<String, Any>?, promise: Promise ->
      val modelFile = getModelFile(modelName)
      val overwrite = (options?.get("overwrite") as? Boolean) ?: false
      
      // Check if already downloading
      if (activeDownloads.containsKey(modelName)) {
        promise.reject("ERR_ALREADY_DOWNLOADING", "This model is already being downloaded", null)
        return@AsyncFunction
      }
      
      // Check if already exists
      if (modelFile.exists() && !overwrite) {
        promise.resolve(true)
        return@AsyncFunction
      }
      
      // Start download in coroutine
      val promiseSettled = AtomicBoolean(false)

      val downloadJob = CoroutineScope(Dispatchers.IO).launch {
        val timeoutMillis = (options?.get("timeout") as? Number)?.toLong() ?: 30_000L
        val headers = options?.get("headers") as? Map<String, Any>
        val tempFile = File(modelFile.absolutePath + ".temp")

        try {
          val (bytesDownloaded, totalBytes) = downloadWithTimeout(
            url = url,
            timeoutMillis = timeoutMillis,
            headers = headers,
            tempFile = tempFile,
            isActive = { this.isActive },
            onProgress = { downloaded, total, progress ->
              sendEvent(
                "downloadProgress",
                mapOf(
                  "modelName" to modelName,
                  "url" to url,
                  "bytesDownloaded" to downloaded,
                  "totalBytes" to total,
                  "progress" to progress,
                  "status" to "downloading"
                )
              )
            }
          )

          if (modelFile.exists()) {
            modelFile.delete()
          }

          if (!tempFile.renameTo(modelFile)) {
            throw IOException("Failed to rename downloaded file")
          }

          sendEvent(
            "downloadProgress",
            mapOf(
              "modelName" to modelName,
              "url" to url,
              "bytesDownloaded" to modelFile.length(),
              "totalBytes" to if (totalBytes > 0) totalBytes else modelFile.length(),
              "progress" to 1.0,
              "status" to "completed"
            )
          )

          withContext(Dispatchers.Main) {
            if (promiseSettled.compareAndSet(false, true)) {
              promise.resolve(true)
            }
          }
        } catch (e: TimeoutCancellationException) {
          sendEvent(
            "downloadProgress",
            mapOf(
              "modelName" to modelName,
              "url" to url,
              "status" to "timeout",
              "error" to "Timed out after ${timeoutMillis}ms"
            )
          )

          withContext(Dispatchers.Main) {
            if (promiseSettled.compareAndSet(false, true)) {
              promise.reject(
                "ERR_DOWNLOAD_TIMEOUT",
                "Download timed out after ${timeoutMillis}ms",
                e
              )
            }
          }
        } catch (e: CancellationException) {
          sendEvent(
            "downloadProgress",
            mapOf(
              "modelName" to modelName,
              "url" to url,
              "status" to "cancelled"
            )
          )

          withContext(Dispatchers.Main) {
            if (promiseSettled.compareAndSet(false, true)) {
              promise.reject("ERR_DOWNLOAD_CANCELLED", "Download was cancelled", e)
            }
          }

          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Error downloading model: ${e.message}", e)
          sendEvent(
            "downloadProgress",
            mapOf(
              "modelName" to modelName,
              "url" to url,
              "status" to "error",
              "error" to (e.message ?: "Unknown error")
            )
          )

          withContext(Dispatchers.Main) {
            if (promiseSettled.compareAndSet(false, true)) {
              promise.reject("ERR_DOWNLOAD", "Failed to download model: ${e.message}", e)
            }
          }
        } finally {
          if (tempFile.exists()) {
            tempFile.delete()
          }
          activeDownloads.remove(modelName)
        }
      }

      activeDownloads[modelName] = downloadJob

      downloadJob.invokeOnCompletion { cause ->
        if (cause is CancellationException && promiseSettled.compareAndSet(false, true)) {
          CoroutineScope(Dispatchers.Main).launch {
            promise.reject("ERR_DOWNLOAD_CANCELLED", "Download was cancelled", cause)
          }
        }
      }
    }
    
    // Cancel download
    AsyncFunction("cancelDownload") { modelName: String, promise: Promise ->
      val job = activeDownloads[modelName]
      if (job != null) {
        job.cancel()
        activeDownloads.remove(modelName)
        promise.resolve(true)
      } else {
        promise.resolve(false)
      }
    }
    
    // Create model from downloaded file
    AsyncFunction("createModelFromDownloaded") { modelName: String, maxTokens: Int?, topK: Int?, temperature: Double?, randomSeed: Int?, promise: Promise ->
      val modelFile = getModelFile(modelName)
      
      if (!modelFile.exists()) {
        promise.reject("ERR_MODEL_NOT_FOUND", "Model $modelName is not downloaded", null)
        return@AsyncFunction
      }
      
      try {
        val handle = createModelInternal(
          modelFile.absolutePath,
          maxTokens ?: 1024,
          topK ?: 40,
          temperature ?: 0.7,
          randomSeed ?: 42
        )
        // Explicitly cast to avoid ambiguity
        promise.resolve(handle as Int)
      } catch (e: Exception) {
        Log.e(TAG, "Error creating model from downloaded file: ${e.message}", e)
        promise.reject("ERR_CREATE_MODEL", "Failed to create model: ${e.message}", e)
      }
    }
  }
}