package expo.modules.llmmediapipe

import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith
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
}
