package com.example.niimbot_label_printer

import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.or
import kotlin.math.ceil

// https://github.com/AndBondStyle/niimprint/blob/main/readme.md
class NiimbotPrinter(private val context: Context, private val bluetoothSocket: BluetoothSocket) {

    private suspend fun sendCommand(requestCode: Byte, data: ByteArray, timeoutMs: Int = 4000): ByteArray = withContext(Dispatchers.IO) {
        val output = bluetoothSocket.outputStream
        val input = bluetoothSocket.inputStream
        val packet = createPacket(requestCode, data)

        android.util.Log.d("NiimbotPrinter", "sendCommand -> 0x${String.format("%02X", requestCode)}, dataSize=${data.size}")

        // Clear any stale bytes before sending a new command
        if (input.available() > 0) {
            val drainBuffer = ByteArray(1024)
            while (input.available() > 0) {
                val drained = input.read(drainBuffer)
                android.util.Log.d("NiimbotPrinter", "sendCommand cleared $drained stale bytes before command 0x${String.format("%02X", requestCode)}")
                if (drained <= 0) break
            }
        }

        // Write packet
        output.write(packet)
        output.flush()

        // Wait for response with timeout
        val pollIntervalMs = 20
        var waited = 0
        val buffer = ByteArray(1024)

        while (input.available() == 0 && waited < timeoutMs) {
            delay(pollIntervalMs.toLong())
            waited += pollIntervalMs
        }

        if (input.available() == 0) {
            android.util.Log.e(
                "NiimbotPrinter",
                "sendCommand timeout waiting for response to 0x${String.format("%02X", requestCode)} after ${timeoutMs}ms"
            )
            throw java.io.IOException("No response for command 0x${String.format("%02X", requestCode)} within ${timeoutMs}ms")
        }

        val bytesRead = input.read(buffer)
        if (bytesRead <= 0) {
            android.util.Log.e("NiimbotPrinter", "sendCommand received empty response for 0x${String.format("%02X", requestCode)}")
            throw java.io.IOException("Empty response for command 0x${String.format("%02X", requestCode)}")
        }

        val response = buffer.copyOfRange(0, bytesRead)
        android.util.Log.d(
            "NiimbotPrinter",
            "sendCommand <- 0x${String.format("%02X", requestCode)}, bytes=$bytesRead, data=${response.joinToString(",") { String.format("0x%02X", it) }}"
        )
        return@withContext response
    }

    private fun createPacket(type: Byte, data: ByteArray): ByteArray {
        val packetData = ByteBuffer.allocate(data.size + 7) // Aumentamos el tamaño en 1
            .put(0x55.toByte()).put(0x55.toByte()) // Header
            .put(type)
            .put(data.size.toByte())
            .put(data)

        var checksum = type.toInt() xor data.size
        data.forEach { checksum = checksum xor it.toInt() }

        packetData.put(checksum.toByte())
            .put(0xAA.toByte()).put(0xAA.toByte()) // Footer

        return packetData.array()
    }

    suspend fun printBitmap(bitmap: Bitmap, density: Int = 3, labelType: Int = 1, quantity: Int = 1, rotate: Boolean = false, invertColor: Boolean = false) {
        android.util.Log.d("NiimbotPrinter", "printBitmap: starting, initial size ${bitmap.width}x${bitmap.height}")
        var bitmap = bitmap
        var width:Int = bitmap.width
        var height:Int = bitmap.height
        
        if (rotate) {
            bitmap = rotateBitmap90Degrees(bitmap)
            width = bitmap.width
            height = bitmap.height
            android.util.Log.d("NiimbotPrinter", "printBitmap: after rotate ${width}x${height}")
        }

        if(invertColor) {
            bitmap = bitmap.invert()
        }
        
        // Convert bitmap to pure black and white (1-bit)
        // This ensures the printer receives a true black and white bitmap
        bitmap = convertToBlackAndWhite(bitmap)
        
        // Save debug bitmap AFTER conversion to verify it worked
        try {
            val externalFilesDir = context.getExternalFilesDir(null)
            if (externalFilesDir != null) {
                val file = java.io.File(externalFilesDir, "debug_bitmap_after_bw_${System.currentTimeMillis()}.png")
                java.io.FileOutputStream(file).use { outputStream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
                }
                android.util.Log.d("NiimbotPrinter", "Saved bitmap AFTER black/white conversion to: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            android.util.Log.w("NiimbotPrinter", "Failed to save debug bitmap after conversion: ${e.message}")
        }
        
        var printStarted = false
        try {
            // Step 0: Quick cleanup (non-blocking, short timeout)
            try {
                android.util.Log.d("NiimbotPrinter", "Step 0: Quick cleanup check")
                // Use shorter timeout for cleanup - don't wait if printer is busy
                val cleanupResponse = sendCommand(0xF3.toByte(), byteArrayOf(1), timeoutMs = 1000)
                if (cleanupResponse.size >= 5 && cleanupResponse[4] != 0.toByte()) {
                    delay(50)
                }
            } catch (e: Exception) {
                // Ignore cleanup errors - printer might already be idle
                android.util.Log.d("NiimbotPrinter", "Cleanup skipped (printer may be idle)")
            }
            
            android.util.Log.d("NiimbotPrinter", "Step 1: Setting label density=$density")
            val densityOk = setLabelDensity(density)
            android.util.Log.d("NiimbotPrinter", "setLabelDensity result: $densityOk")
            if (!densityOk) {
                throw Exception("Failed to set label density")
            }
            
            android.util.Log.d("NiimbotPrinter", "Step 2: Setting label type=$labelType")
            val typeOk = setLabelType(labelType)
            android.util.Log.d("NiimbotPrinter", "setLabelType result: $typeOk")
            if (!typeOk) {
                throw Exception("Failed to set label type")
            }
            
            android.util.Log.d("NiimbotPrinter", "Step 3: Starting print")
            val startOk = startPrint()
            android.util.Log.d("NiimbotPrinter", "startPrint result: $startOk")
            if (!startOk) {
                throw Exception("Failed to start print")
            }
            printStarted = true
            
            // Wait a moment after startPrint before calling startPagePrint
            // The original code's blocking reads provide this delay implicitly
            delay(200)
            
            android.util.Log.d("NiimbotPrinter", "Step 4: Starting page print")
            // startPagePrint may timeout, but that's OK - the printer is still ready
            // Just send the command and continue
            val pageStartOk = startPagePrint()
            android.util.Log.d("NiimbotPrinter", "startPagePrint result: $pageStartOk")
            
            // Wait longer after startPagePrint before setting dimensions
            // The printer may need time to initialize
            delay(500)
            
            android.util.Log.d("NiimbotPrinter", "Step 5: Setting dimensions width=$width, height=$height")
            android.util.Log.d("NiimbotPrinter", "Bitmap actual dimensions: ${bitmap.width}x${bitmap.height}")
            android.util.Log.d("NiimbotPrinter", "For 50mm x 25mm label: expected 400x200px, sending to printer: width=$width, height=$height")
            
            // Printer protocol expects (height, width) order - matching the working implementation
            // The working code calls setDimension(height, width) which sends [height, width] bytes
            // For 384x192 image, we send [192, 384] = [0x00C0, 0x0180]
            val dimOk = setDimension(height, width) // Send height first, then width (protocol order)
            android.util.Log.d("NiimbotPrinter", "setDimension called with height=$height, width=$width (protocol order - swapped) result: $dimOk")
            
            if (!dimOk) {
                android.util.Log.w("NiimbotPrinter", "setDimension returned false - printer may not accept these dimensions")
            } else {
                android.util.Log.d("NiimbotPrinter", "✓ Dimensions accepted by printer: ${bitmap.width}x${bitmap.height} pixels")
            }
            // Continue even if setDimension returns false - the printer might still accept the dimensions
            // Some printers return 0x00 even when they accept the dimensions
            if (!dimOk) {
                android.util.Log.w("NiimbotPrinter", "setDimension returned false, but continuing anyway")
            }
            
            android.util.Log.d("NiimbotPrinter", "Step 6: Setting quantity=$quantity")
            val qtyOk = setQuantity(quantity)
            android.util.Log.d("NiimbotPrinter", "setQuantity result: $qtyOk")
            if (!qtyOk) {
                throw Exception("Failed to set quantity")
            }
            
            android.util.Log.d("NiimbotPrinter", "Step 7: Encoding and sending image")
            // Verify bitmap before encoding - check a few pixels to confirm it's the converted bitmap
            var preEncodeBlack = 0
            var preEncodeTotal = 0
            for (y in 0 until minOf(bitmap.height, 10)) {
                for (x in 0 until minOf(bitmap.width, 10)) {
                    preEncodeTotal++
                    val pixel = bitmap.getPixel(x, y)
                    if (pixel == 0xFF000000.toInt()) {
                        preEncodeBlack++
                    }
                }
            }
            android.util.Log.d("NiimbotPrinter", "Bitmap BEFORE encodeImage (first ${preEncodeTotal} pixels): $preEncodeBlack black pixels")
            
            val packets = encodeImage(bitmap)
            android.util.Log.d("NiimbotPrinter", "Encoded ${packets.size} packets")
            
            // Log a sample packet from a line with data to verify format
            if (packets.size > 20) {
                // Get a packet from around line 20-22 where we know there's data
                val samplePacket = packets[21] // Line 21
                val sampleHex = samplePacket.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
                android.util.Log.d("NiimbotPrinter", "Sample packet (line 21, full packet): $sampleHex")
                android.util.Log.d("NiimbotPrinter", "Packet structure: header=${samplePacket[0]}${samplePacket[1]}, cmd=${samplePacket[2]}, len=${samplePacket[3]}, data starts at index 4")
            }
            
            var packetCount = 0
            for (packet in packets) {
            bluetoothSocket.outputStream.write(packet)
            bluetoothSocket.outputStream.flush()
                packetCount++
                if (packetCount % 50 == 0) {
                    android.util.Log.d("NiimbotPrinter", "Sent $packetCount/${packets.size} packets")
                }
                // Delay between each packet (exactly like original implementation)
                delay(10) // Small pause between packets
            }
            android.util.Log.d("NiimbotPrinter", "All ${packets.size} packets sent")
            
            // Wait a moment after sending all packets to ensure printer has processed them
            delay(200)

            android.util.Log.d("NiimbotPrinter", "Step 8: Ending page print")
            var endPageAttempts = 0
            while (!endPagePrint() && endPageAttempts < 100) {
            delay(50)
                endPageAttempts++
        }
            android.util.Log.d("NiimbotPrinter", "endPagePrint completed after $endPageAttempts attempts")

            android.util.Log.d("NiimbotPrinter", "Step 9: Polling print status")
            var statusAttempts = 0
            var lastProgress1 = -1
            var lastPage = -1
            var stableCount = 0
            var noProgressCount = 0
            val maxAttempts = 50 // 5 seconds max (50 * 100ms)
            
            while (statusAttempts < maxAttempts) {
            val status = getPrintStatus()
                val currentPage = status["page"] ?: 0
                val progress1 = status["progress1"] ?: 0
                val progress2 = status["progress2"] ?: 0
                
                // Check if printing is complete:
                // 1. Page count matches quantity
                if (currentPage >= quantity) {
                    android.util.Log.d("NiimbotPrinter", "Print completed: $currentPage pages printed (requested $quantity)")
                    break
                }
                
                // 2. Progress1 reaches 100 (printing complete)
                if (progress1 >= 100) {
                    stableCount++
                    if (stableCount >= 3) {
                        android.util.Log.d("NiimbotPrinter", "Print completed: progress1=$progress1 (stable for 3 checks)")
                        break
                    }
                } else {
                    stableCount = 0
                }
                
                // 3. If progress hasn't changed for 10 checks and we've made some progress, assume complete
                if (progress1 == lastProgress1 && currentPage == lastPage && progress1 > 0) {
                    noProgressCount++
                    if (noProgressCount >= 10) {
                        android.util.Log.d("NiimbotPrinter", "Print appears complete: progress1=$progress1 stable for 10 checks, page=$currentPage")
                        break
                    }
                } else {
                    noProgressCount = 0
                }
                
                if (statusAttempts % 5 == 0 || progress1 != lastProgress1 || currentPage != lastPage) {
                    android.util.Log.d("NiimbotPrinter", "Print status: page=$currentPage, progress1=$progress1, progress2=$progress2")
                }
                lastProgress1 = progress1
                lastPage = currentPage
            delay(100)
                statusAttempts++
            }
            if (statusAttempts >= maxAttempts) {
                android.util.Log.w("NiimbotPrinter", "Print status polling timed out after ${statusAttempts * 100}ms - stopping anyway")
            }

            android.util.Log.d("NiimbotPrinter", "Step 10: Ending print")
            endPrint()
            android.util.Log.d("NiimbotPrinter", "printBitmap: completed")
        } catch (e: Exception) {
            android.util.Log.e("NiimbotPrinter", "printBitmap failed: ${e.message}", e)
            // Always cleanup on error
            if (printStarted) {
                try {
                    android.util.Log.d("NiimbotPrinter", "Cleaning up after error")
                    endPrint()
                } catch (cleanupError: Exception) {
                    android.util.Log.w("NiimbotPrinter", "Failed to cleanup after error: ${cleanupError.message}")
                }
            }
            throw e // Re-throw to let caller handle it
        }
    }

    fun rotateBitmap90Degrees(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply {
            postRotate(90f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun Bitmap.rotate90Clockwise(): Bitmap {
        val matrix = Matrix()
        matrix.setRotate(90f)
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }


    private suspend fun loadImageFromAssets(imageName: String): Bitmap =
        withContext(Dispatchers.IO) {
            val inputStream = context.assets.open(imageName)
            BitmapFactory.decodeStream(inputStream)
        }

    private fun encodeImage(bitmap: Bitmap): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var totalBlackPixels = 0
        var totalPixels = 0
        var sampleLogged = false

        // Use original simple approach - check pixels directly from bitmap
        // This matches the original working implementation exactly
        
        // Debug: Check a sample area that should have black pixels (QR code area around x=108-292, y=8-88 for 50x25mm label)
        var sampleBlackCount = 0
        var sampleTotal = 0
        for (y in 18..22) {
            for (x in 108..292) {
                if (x < bitmap.width && y < bitmap.height) {
                    sampleTotal++
                    val pixel = bitmap.getPixel(x, y)
                    val a = (pixel shr 24) and 0xFF
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                    if (pixel == 0xFF000000.toInt() || (a >= 128 && brightness < 128)) {
                        sampleBlackCount++
                    }
                }
            }
        }
        android.util.Log.d("NiimbotPrinter", "Sample QR area (lines 18-22, x 108-292): $sampleBlackCount/$sampleTotal black pixels")
        
        // First, verify the bitmap has content by checking a known area
        // For a 384x192 image with QR code on right side, check around x=216-384, y=16-184
        var verificationBlack = 0
        var verificationTotal = 0
        val checkStartX = (bitmap.width * 0.56).toInt()
        val checkEndX = bitmap.width - 16
        val checkStartY = 16
        val checkEndY = minOf(bitmap.height - 16, checkStartY + 50)
        for (y in checkStartY until checkEndY) {
            for (x in checkStartX until checkEndX) {
                verificationTotal++
                val pixel = bitmap.getPixel(x, y)
                val a = (pixel shr 24) and 0xFF
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                if (a >= 128 && brightness < 128) {
                    verificationBlack++
                }
            }
        }
        android.util.Log.d("NiimbotPrinter", "Bitmap verification (QR area x=$checkStartX-$checkEndX, y=$checkStartY-$checkEndY): $verificationBlack/$verificationTotal black pixels")
        
        var linesWithData = 0
        // Normal encoding: 1=black (heat on), 0=white (no heat)
        // This gives us black text/QR codes on white background (label color)
        val invertBits = false // Set to false: 1=black, 0=white (normal thermal printing)
        
        for (y in 0 until bitmap.height) {
            val lineData = ByteArray(ceil(bitmap.width / 8.0).toInt())
            var lineBlackPixels = 0
            for (x in 0 until bitmap.width) {
                totalPixels++
                val pixel = bitmap.getPixel(x, y)
                
                // Extract ARGB components
                val a = (pixel shr 24) and 0xFF
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // Log first few pixels for debugging
                if (!sampleLogged && totalPixels <= 10) {
                    android.util.Log.d("NiimbotPrinter", "Pixel[$x,$y]: 0x${String.format("%08X", pixel)}, ARGB=($a,$r,$g,$b)")
                }
                
                // Check for black pixel: After convertToBlackAndWhite, all pixels should be
                // either 0xFF000000 (pure black) or 0xFFFFFFFF (pure white)
                // So we only need to check for pure black
                val isBlack = (pixel == 0xFF000000.toInt())
                
                // Debug: Log if we find non-black/non-white pixels (shouldn't happen after conversion)
                if (pixel != 0xFF000000.toInt() && pixel != 0xFFFFFFFF.toInt() && totalPixels < 20) {
                    android.util.Log.w("NiimbotPrinter", "Found non-BW pixel after conversion: 0x${String.format("%08X", pixel)} at ($x,$y)")
                }
                
                val byteIndex = x / 8
                val bitIndex = 7 - (x % 8) // MSB first (matches original code)
                val currentByte = lineData[byteIndex].toInt() and 0xFF
                val bitMask = 1 shl bitIndex
                
                if (invertBits) {
                    // Inverted: 0=black, 1=white
                    // So for black pixels, we set bit to 0 (don't set it)
                    // For white pixels, we set bit to 1
                    if (!isBlack) { // White pixel - set bit to 1
                        lineData[byteIndex] = (currentByte or bitMask).toByte()
                    }
                    // Black pixel - bit stays 0 (default)
                    if (isBlack) {
                        totalBlackPixels++
                        lineBlackPixels++
                    }
                } else {
                    // Normal: 1=black, 0=white
                    if (isBlack) {
                        totalBlackPixels++
                        lineBlackPixels++
                        lineData[byteIndex] = (currentByte or bitMask).toByte()
                    }
                }
            }
            
            // Check if this line has any black pixels
            val hasData = lineData.any { (it.toInt() and 0xFF) != 0 }
            if (hasData) {
                linesWithData++
                // Log first few lines with data - show more bytes and verify non-zero
                if (linesWithData <= 5) {
                    val dataHex = lineData.take(20).joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
                    val nonZeroBytes = lineData.count { (it.toInt() and 0xFF) != 0 }
                    // Also show the full line data for debugging
                    val fullDataHex = lineData.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
                    android.util.Log.d("NiimbotPrinter", "Line $y has $lineBlackPixels black pixels, $nonZeroBytes non-zero bytes")
                    android.util.Log.d("NiimbotPrinter", "Line $y first 20 bytes: $dataHex")
                    android.util.Log.d("NiimbotPrinter", "Line $y full data (${lineData.size} bytes): $fullDataHex")
                }
            } else if (lineBlackPixels > 0) {
                // This should never happen - if we detected black pixels, data should be non-zero
                android.util.Log.e("NiimbotPrinter", "ERROR: Line $y has $lineBlackPixels black pixels but lineData is all zeros!")
                // Debug: show which pixels were detected as black
                android.util.Log.e("NiimbotPrinter", "Line $y lineData size: ${lineData.size}, bitmap width: ${bitmap.width}")
            }
            
            if (!sampleLogged && y == 0) {
                sampleLogged = true
            }

            // Header format: [line_high, line_low, count1, count2, count3, flag]
            // Protocol uses BIG-ENDIAN for line number (high byte first, then low byte)
            // This matches the working implementation and the packet log shows 00 15 for line 21
            val header = ByteBuffer.allocate(6)
                .order(ByteOrder.BIG_ENDIAN) // Explicitly set big-endian
                .putShort(y.toShort()) // High byte first, then low byte
                .put(0.toByte()).put(0.toByte()).put(0.toByte()) // counts
                .put(1.toByte())
                .array()

            val packetData = header + lineData
            packets.add(createPacket(0x85.toByte(), packetData))
        }

        val blackPercentage = if (totalPixels > 0) (totalBlackPixels * 100.0 / totalPixels) else 0.0
        android.util.Log.d("NiimbotPrinter", "encodeImage: ${bitmap.width}x${bitmap.height}, blackPixels=$totalBlackPixels/$totalPixels (${String.format("%.1f", blackPercentage)}%), linesWithData=$linesWithData/${bitmap.height}")

        return packets
    }

    private fun Bitmap.invert(): Bitmap {
        val invertedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(invertedBitmap)
        val paint = android.graphics.Paint()
        val colorMatrix = android.graphics.ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(this, 0f, 0f, paint)
        return invertedBitmap
    }
    
    /**
     * Convert bitmap to pure black and white (1-bit)
     * This ensures the printer receives a true black and white bitmap image
     * Any pixel darker than 50% gray becomes pure black (0xFF000000)
     * Any pixel lighter than 50% gray becomes pure white (0xFFFFFFFF)
     */
    private fun convertToBlackAndWhite(bitmap: Bitmap): Bitmap {
        val bwBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        var convertedBlack = 0
        var convertedWhite = 0
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val a = (pixel shr 24) and 0xFF
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // Calculate brightness
                val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                
                // Convert to pure black or white based on 50% threshold
                val bwPixel = if (a >= 128 && brightness < 128) {
                    convertedBlack++
                    0xFF000000.toInt() // Pure black
                } else {
                    convertedWhite++
                    0xFFFFFFFF.toInt() // Pure white
                }
                
                bwBitmap.setPixel(x, y, bwPixel)
            }
        }
        
        // Verify the conversion worked by checking a few pixels
        var verifyBlack = 0
        var verifyWhite = 0
        var verifyOther = 0
        for (y in 0 until minOf(10, bwBitmap.height)) {
            for (x in 0 until minOf(10, bwBitmap.width)) {
                val pixel = bwBitmap.getPixel(x, y)
                when (pixel) {
                    0xFF000000.toInt() -> verifyBlack++
                    0xFFFFFFFF.toInt() -> verifyWhite++
                    else -> verifyOther++
                }
            }
        }
        android.util.Log.d("NiimbotPrinter", "Converted bitmap to black and white: ${bitmap.width}x${bitmap.height}, black=$convertedBlack, white=$convertedWhite")
        android.util.Log.d("NiimbotPrinter", "Verification (first 100 pixels): black=$verifyBlack, white=$verifyWhite, other=$verifyOther")
        if (verifyOther > 0) {
            android.util.Log.e("NiimbotPrinter", "ERROR: Found $verifyOther non-BW pixels after conversion!")
        }
        
        // Additional verification: check if we have any black pixels at all
        if (convertedBlack == 0) {
            android.util.Log.e("NiimbotPrinter", "WARNING: convertToBlackAndWhite produced 0 black pixels! Original bitmap may be all white or conversion logic may be wrong.")
            // Debug: check original bitmap for dark pixels
            var originalDark = 0
            var originalTotal = 0
            for (y in 0 until minOf(bitmap.height, 50)) {
                for (x in 0 until minOf(bitmap.width, 50)) {
                    originalTotal++
                    val pixel = bitmap.getPixel(x, y)
                    val a = (pixel shr 24) and 0xFF
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                    if (a >= 128 && brightness < 128) {
                        originalDark++
                    }
                }
            }
            android.util.Log.d("NiimbotPrinter", "Original bitmap sample (first $originalTotal pixels): $originalDark dark pixels (brightness < 128, alpha >= 128)")
        }
        
        return bwBitmap
    }

    suspend fun setLabelDensity(n: Int): Boolean {
        require(n in 1..5) { "Density must be between 1 and 5" }
        val response = sendCommand(0x21, byteArrayOf(n.toByte()))
        return response[4] != 0.toByte()
    }

    suspend fun setLabelType(n: Int): Boolean {
        require(n in 1..3) { "Label type must be between 1 and 3" }
        val response = sendCommand(0x23, byteArrayOf(n.toByte()))
        return response[4] != 0.toByte()
    }

    suspend fun startPrint(): Boolean {
        val response = sendCommand(0x01, byteArrayOf(1))
        return response[4] != 0.toByte()
    }

    suspend fun endPrint(): Boolean {
        val response = sendCommand(0xF3.toByte(), byteArrayOf(1))
        return response[4] != 0.toByte()
    }

    suspend fun startPagePrint(): Boolean {
        // Use shorter timeout - if it times out, we'll continue anyway
        // The printer may not respond immediately but is still ready
        try {
            val response = sendCommand(0x03, byteArrayOf(1), timeoutMs = 2000)
            return response[4] != 0.toByte()
        } catch (e: Exception) {
            // Timeout is expected - printer may not respond but is still ready
            android.util.Log.d("NiimbotPrinter", "startPagePrint timeout (expected): ${e.message}")
            return true // Assume success if timeout
        }
    }

    suspend fun endPagePrint(): Boolean {
        val response = sendCommand(0xE3.toByte(), byteArrayOf(1))
        return response[4] != 0.toByte()
    }

    suspend fun allowPrintClear(): Boolean {
        val response = sendCommand(0x20, byteArrayOf(1))
        return response[4] != 0.toByte()
    }

    suspend fun setDimension(width: Int, height: Int): Boolean {
        // Note: Parameter names are swapped from protocol expectation
        // When called as setDimension(height, width), this function receives:
        // - width parameter = actual image height value
        // - height parameter = actual image width value
        // This sends [height, width] bytes to match the protocol
        android.util.Log.d("NiimbotPrinter", "setDimension called with width=$width, height=$height")
        android.util.Log.d("NiimbotPrinter", "Note: parameters are swapped - sending [height=$width, width=$height] bytes to protocol")
        // Use big-endian byte order to match working implementation
        // This creates: [height_high, height_low, width_high, width_low] in big-endian
        val data = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN) // Explicitly set big-endian
            .putShort(width.toShort())  // This is actually the height value (first param)
            .putShort(height.toShort()) // This is actually the width value (second param)
            .array()
        android.util.Log.d("NiimbotPrinter", "setDimension data bytes (height=$width=0x${String.format("%04X", width)}, width=$height=0x${String.format("%04X", height)}): ${data.joinToString(", ") { String.format("0x%02X", it.toInt() and 0xFF) }}")
        val response = sendCommand(0x13, data)
        android.util.Log.d("NiimbotPrinter", "setDimension response: ${response.joinToString(", ") { String.format("0x%02X", it.toInt() and 0xFF) }}")
        // Original code just checks response[4] != 0
        val result = if (response.size > 4) response[4].toInt() and 0xFF else 0
        android.util.Log.d("NiimbotPrinter", "setDimension result: $result")
        return result != 0
    }

    suspend fun setQuantity(n: Int): Boolean {
        // Use little-endian byte order to match protocol
        val data = ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(n.toShort())
            .array()
        android.util.Log.d("NiimbotPrinter", "setQuantity: n=$n, data=${data.joinToString(", ") { String.format("0x%02X", it.toInt() and 0xFF) }}")
        val response = sendCommand(0x15, data)
        android.util.Log.d("NiimbotPrinter", "setQuantity response: ${response.joinToString(", ") { String.format("0x%02X", it.toInt() and 0xFF) }}")
        return response[4] != 0.toByte()
    }

    suspend fun getPrintStatus(): Map<String, Int> {
        val response = sendCommand(0xA3.toByte(), byteArrayOf(1))
        // Ensure we have enough bytes in the response
        if (response.size < 9) {
            // Response too short, return default values
            return mapOf(
                "page" to 0,
                "progress1" to 0,
                "progress2" to 0
            )
        }
        val data = response.copyOfRange(4, response.size - 3)
        // Ensure data has at least 4 bytes
        if (data.size < 4) {
            // If we have at least 2 bytes, use them for page, otherwise default
            // Parse as big-endian (matches original Kotlin code - ByteBuffer default)
            val page = if (data.size >= 2) {
                ByteBuffer.wrap(data.copyOfRange(0, 2)).short.toInt()
            } else {
                0
            }
            return mapOf(
                "page" to page,
                "progress1" to 0,
                "progress2" to 0
            )
        }
        // Parse page as big-endian (matches original Kotlin code)
        // Original code: ByteBuffer.wrap(data.copyOfRange(0, 2)).short.toInt()
        val page = ByteBuffer.wrap(data.copyOfRange(0, 2)).short.toInt()
        return mapOf(
            "page" to page,
            "progress1" to (data[2].toInt() and 0xFF),
            "progress2" to (data[3].toInt() and 0xFF)
        )
    }

    suspend fun getInfo(key: Byte): Any {
        val response = sendCommand(0x40, byteArrayOf(key))
        val data = response.copyOfRange(4, response.size - 3)
        return when (key) {
            11.toByte() -> data.joinToString("") { "%02x".format(it) } // DEVICESERIAL
            9.toByte(), 12.toByte() -> ByteBuffer.wrap(data).int / 100.0 // SOFTVERSION, HARDVERSION
            else -> ByteBuffer.wrap(data).int
        }
    }

    suspend fun getRfid(): Map<String, Any>? {
        val response = sendCommand(0x1A, byteArrayOf(1))
        val data = response.copyOfRange(4, response.size - 3)

        if (data[0] == 0.toByte()) return null

        var idx = 8
        val barcodeLen = data[idx++].toInt()
        val barcode = String(data.copyOfRange(idx, idx + barcodeLen))
        idx += barcodeLen

        val serialLen = data[idx++].toInt()
        val serial = String(data.copyOfRange(idx, idx + serialLen))
        idx += serialLen

        val totalLen = ByteBuffer.wrap(data, idx, 2).short.toInt()
        val usedLen = ByteBuffer.wrap(data, idx + 2, 2).short.toInt()
        val type = data[idx + 4]

        return mapOf(
            "uuid" to data.copyOfRange(0, 8).joinToString("") { "%02x".format(it) },
            "barcode" to barcode,
            "serial" to serial,
            "used_len" to usedLen,
            "total_len" to totalLen,
            "type" to type
        )
    }

    suspend fun heartbeat(): Map<String, Int?> {
        val response = sendCommand(0xDC.toByte(), byteArrayOf(1))
        val data = response.copyOfRange(4, response.size - 3)

        return when (data.size) {
            20 -> mapOf(
                "closing_state" to null,
                "power_level" to null,
                "paper_state" to data[18].toInt(),
                "rfid_read_state" to data[19].toInt()
            )

            13 -> mapOf(
                "closing_state" to data[9].toInt(),
                "power_level" to data[10].toInt(),
                "paper_state" to data[11].toInt(),
                "rfid_read_state" to data[12].toInt()
            )

            19 -> mapOf(
                "closing_state" to data[15].toInt(),
                "power_level" to data[16].toInt(),
                "paper_state" to data[17].toInt(),
                "rfid_read_state" to data[18].toInt()
            )

            10 -> mapOf(
                "closing_state" to data[8].toInt(),
                "power_level" to data[9].toInt(),
                "paper_state" to null,
                "rfid_read_state" to data[8].toInt()
            )

            9 -> mapOf(
                "closing_state" to data[8].toInt(),
                "power_level" to null,
                "paper_state" to null,
                "rfid_read_state" to null
            )

            else -> mapOf(
                "closing_state" to null,
                "power_level" to null,
                "paper_state" to null,
                "rfid_read_state" to null
            )
        }
    }
}