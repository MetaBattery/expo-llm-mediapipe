package expo.modules.llmmediapipe

import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class DownloadWithTimeoutTest {
  private lateinit var serverSocket: ServerSocket
  private lateinit var tempFile: File
  private var serverThread = Thread()

  @Before
  fun setUp() {
    serverSocket = ServerSocket(0)
    tempFile = File.createTempFile("download", ".tmp")
  }

  @After
  fun tearDown() {
    if (::serverSocket.isInitialized && !serverSocket.isClosed) {
      serverSocket.close()
    }
    if (::tempFile.isInitialized && tempFile.exists()) {
      tempFile.delete()
    }
    if (serverThread.isAlive) {
      serverThread.interrupt()
      serverThread.join(1000)
    }
  }

  @Test
  fun downloadTimesOutWhenServerStalls() = runTest {
    serverThread = thread {
      serverSocket.use { server ->
        val socket = server.accept()
        try {
          // Keep the connection open without sending any response to simulate a stall.
          Thread.sleep(5000)
        } catch (_: InterruptedException) {
          // Allow the thread to exit when interrupted during teardown.
        } finally {
          socket.close()
        }
      }
    }

    val timeoutMillis = 1_000L
    val url = "http://127.0.0.1:${serverSocket.localPort}/"

    assertFailsWith<TimeoutCancellationException> {
      downloadWithTimeout(
        url = url,
        timeoutMillis = timeoutMillis,
        headers = null,
        tempFile = tempFile,
        isActive = { true },
        onProgress = { _, _, _ -> }
      )
    }
  }

  @Test
  fun parseContentLengthHandlesLargeUnderscoredValues() {
    val totalBytes = parseContentLength("3_200_000_000")

    assertNotNull(totalBytes)
    assertEquals(3_200_000_000L, totalBytes)

    val bytesDownloaded = 1_600_000_000L
    val progress = if (totalBytes > 0) bytesDownloaded.toDouble() / totalBytes else 0.0

    assertTrue(progress > 0.0, "Progress should be greater than zero for partial download")
  }
}
