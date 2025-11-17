package com.example.niimbot_label_printer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import androidx.annotation.NonNull
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

/** NiimbotLabelPrinterPlugin */
class NiimbotLabelPrinterPlugin : FlutterPlugin, MethodCallHandler {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private var TAG: String = "====> NiimbotLabelPrinterPlugin: "
    private lateinit var channel: MethodChannel
    private lateinit var mContext: Context
    private var state: Boolean = false

    //val pluginActivity: Activity = activity
    //private val application: Application = activity.application
    private val myPermissionCode = 34264
    private var activeResult: Result? = null
    private var permissionGranted: Boolean = false

    private var bluetoothSocket: BluetoothSocket? = null
    private lateinit var mac: String
    private lateinit var niimbotPrinter: NiimbotPrinter
    private val printMutex = Mutex() // Serialize print jobs to prevent concurrent execution

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "niimbot_label_printer")
        channel.setMethodCallHandler(this)
        this.mContext = flutterPluginBinding.applicationContext
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        var sdkversion: Int = Build.VERSION.SDK_INT

        activeResult = result
        permissionGranted = ContextCompat.checkSelfPermission(
            mContext,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        if (call.method == "ispermissionbluetoothgranted") {
            var permission: Boolean = true;
            if (sdkversion >= 31) {
                permission = permissionGranted;
            }
            //solicitar el permiso si no esta consedido
            if (!permission) {
                // Solicitar el permiso si no esta consedido
            }

            result.success(permission)
        } else if (!permissionGranted && sdkversion >= 31) {
            Log.i(
                "warning",
                "Permission bluetooth granted is false, check in settings that the permission of nearby devices is activated"
            )
            return;
        } else if (call.method == "getPlatformVersion") {
            var androidVersion: String = android.os.Build.VERSION.RELEASE;
            result.success("Android ${androidVersion}")
        } else if (call.method == "isBluetoothEnabled") {
            var state: Boolean = false
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                state = true
            }
            result.success(state)
        } else if (call.method == "isConnected") {
            if (bluetoothSocket != null) {
                try {
                    bluetoothSocket?.outputStream?.run {
                        write(" ".toByteArray())
                        result.success(true)
                        //Log.d(TAG, "paso yes coexion ")
                    }
                } catch (e: Exception) {
                    result.success(false)
                    bluetoothSocket = null
                    //mensajeToast("Dispositivo fue desconectado, reconecte")
                    //Log.d(TAG, "state print: ${e.message}")
                }
            } else {
                result.success(false)
                //Log.d(TAG, "no paso es false ")
            }
        } else if (call.method == "getPairedDevices") {
            var lista: List<String> = dispositivosVinculados()

            result.success(lista)
        } else if (call.method == "connect") {
            var macimpresora = call.arguments.toString();
            //Log.d(TAG, "coneccting kt: mac: "+macimpresora);
            if (macimpresora.length > 0) {
                mac = macimpresora;
            } else {
                result.success(false)
            }

            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                    if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                        val device = bluetoothAdapter.getRemoteDevice(mac)
                        bluetoothSocket = device?.createRfcommSocketToServiceRecord(
                            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                        )
                        bluetoothSocket?.connect()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    result.success(false)
                }
            }
        } else if (call.method == "send") {
            val datosImagen = call.arguments as Map<String, Any>

            // B21, B1, B18: máx. 384 píxeles (casi igual a 50 mm * 8 px/mm = 400)
            // D11: máx. 96 píxeles (casi igual a 15 mm * 8 px/mm = 120)
            // B1: 400 ancho x 240 alto
            // Se sacan los porcentajes asi: si es lable 50 x 30 se multiplica por 8 pixeles, ejemplo 50*8=400 y 30*8=240

            // Extraer los datos del mapa
            // Convert List<Int> (0-255) to ByteArray, handling unsigned bytes correctly
            val bytes = (datosImagen["bytes"] as? List<Int>)?.map { (it and 0xFF).toByte() }?.toByteArray()
            val width = (datosImagen["width"] as? Int) ?: 0
            val height = (datosImagen["height"] as? Int) ?: 0
            val rotate = (datosImagen["rotate"] as? Boolean) ?: false
            val invertColor = (datosImagen["invertColor"] as? Boolean) ?: false
            val density = (datosImagen["density"] as? Int) ?: 3
            val labelType = (datosImagen["labelType"] as? Int) ?: 1
            //println("0. width: $width height: $height")

            if (bytes != null && width > 0 && height > 0) {
                // Verifica que el tamaño del buffer sea correcto
                val expectedBufferSize = width * height * 4 // 4 bytes por píxel (ARGB_8888)
                if (bytes.size != expectedBufferSize) {
                    throw IllegalArgumentException("Buffer not large enough for pixels: expected $expectedBufferSize but got ${bytes.size}")
                }
                // Create Bitmap - manually set pixels to ensure correct ARGB format
                // Flutter sends ARGB bytes: A, R, G, B (4 bytes per pixel)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                
                // Verify buffer size matches bitmap requirements
                if (bytes.size < width * height * 4) {
                    throw IllegalArgumentException("Buffer size (${bytes.size}) is smaller than required for ${width}x${height} ARGB_8888 bitmap (${width * height * 4})")
                }
                
                // Debug: Log first few bytes to verify they're correct
                val firstBytes = bytes.take(16).map { it.toInt() and 0xFF }
                android.util.Log.d("NiimbotPrinter", "First 16 bytes (as unsigned): ${firstBytes.joinToString(", ")}")
                
                // Manually set pixels from ARGB bytes to ensure correct format
                // Bytes are in ARGB format: A, R, G, B (4 bytes per pixel)
                val pixels = IntArray(width * height)
                for (i in pixels.indices) {
                    val offset = i * 4
                    if (offset + 3 < bytes.size) {
                        // Read bytes as unsigned (0-255)
                        val a = bytes[offset].toInt() and 0xFF
                        val r = bytes[offset + 1].toInt() and 0xFF
                        val g = bytes[offset + 2].toInt() and 0xFF
                        val b = bytes[offset + 3].toInt() and 0xFF
                        // Pack into ARGB int: (A << 24) | (R << 16) | (G << 8) | B
                        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

                // Debug: Check a few sample pixels to verify they're being set correctly
                val samplePixels = mutableListOf<Int>()
                var blackCount = 0
                var totalCount = 0
                for (y in 0 until minOf(5, height)) {
                    for (x in 0 until minOf(5, width)) {
                        val pixel = bitmap.getPixel(x, y)
                        samplePixels.add(pixel)
                        totalCount++
                        if (pixel == 0xFF000000.toInt()) {
                            blackCount++
                        }
                    }
                }
                android.util.Log.d("NiimbotPrinter", "Sample pixels (first 25): ${samplePixels.take(25).joinToString(", ") { String.format("0x%08X", it) }}")
                android.util.Log.d("NiimbotPrinter", "Sample area black pixels: $blackCount/$totalCount")
                
                // Count black pixels in the entire bitmap BEFORE conversion
                var totalBlackBefore = 0
                var totalPixelsBefore = 0
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        totalPixelsBefore++
                        val pixel = bitmap.getPixel(x, y)
                        val a = (pixel shr 24) and 0xFF
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                        if (a >= 128 && brightness < 128) {
                            totalBlackBefore++
                        }
                    }
                }
                android.util.Log.d("NiimbotPrinter", "Bitmap BEFORE conversion: blackPixels=$totalBlackBefore/$totalPixelsBefore (${(totalBlackBefore * 100.0 / totalPixelsBefore).toInt()}%)")
                
                // Count black pixels in the bitmap for debugging - check entire bitmap
                var blackPixels = 0
                var totalPixels = 0
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        totalPixels++
                        val pixel = bitmap.getPixel(x, y)
                        val a = (pixel shr 24) and 0xFF
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                        if (a >= 128 && brightness < 128) {
                            blackPixels++
                        }
                    }
                }
                android.util.Log.d("NiimbotPrinter", "Full bitmap check: blackPixels=$blackPixels/$totalPixels (${String.format("%.1f", blackPixels * 100.0 / totalPixels)}%)")
                
                // Check specific areas where we expect content (QR code area, text area)
                var qrAreaBlack = 0
                var qrAreaTotal = 0
                // QR code should be around x=216-384 (right side), y=16-184 for 384x192 image
                val qrStartX = (width * 0.56).toInt() // ~216 for 384px width
                val qrEndX = width - 16
                val qrStartY = 16
                val qrEndY = minOf(height - 16, qrStartY + 168)
                for (y in qrStartY until qrEndY) {
                    for (x in qrStartX until qrEndX) {
                        qrAreaTotal++
                        val pixel = bitmap.getPixel(x, y)
                        val a = (pixel shr 24) and 0xFF
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                        if (a >= 128 && brightness < 128) {
                            qrAreaBlack++
                        }
                    }
                }
                android.util.Log.d("NiimbotPrinter", "QR code area check (x=$qrStartX-$qrEndX, y=$qrStartY-$qrEndY): blackPixels=$qrAreaBlack/$qrAreaTotal")
                
                // Save bitmap to file for visual debugging (only in debug builds)
                try {
                    val file = java.io.File(mContext.getExternalFilesDir(null), "debug_bitmap_${System.currentTimeMillis()}.png")
                    val outputStream = java.io.FileOutputStream(file)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.close()
                    android.util.Log.d("NiimbotPrinter", "Saved debug bitmap to: ${file.absolutePath}")
                } catch (e: Exception) {
                    android.util.Log.w("NiimbotPrinter", "Failed to save debug bitmap: ${e.message}")
                }

                android.util.Log.d("NiimbotPrinter", "Created bitmap: ${bitmap.width}x${bitmap.height}, density=$density, labelType=$labelType, rotate=$rotate")
                android.util.Log.d("NiimbotPrinter", "Bitmap will be sent to printer with dimensions: ${bitmap.width}x${bitmap.height} pixels")
                android.util.Log.d("NiimbotPrinter", "For 50mm x 25mm label at 203 DPI: expected 400x200px")
                if (bitmap.width == 400 && bitmap.height == 200) {
                    android.util.Log.d("NiimbotPrinter", "✓ Bitmap dimensions match expected 50mm x 25mm label size")
                } else {
                    android.util.Log.w("NiimbotPrinter", "⚠ Bitmap dimensions (${bitmap.width}x${bitmap.height}) do not match expected 400x200 for 50mm x 25mm label")
                }
                
                bluetoothSocket?.let { socket ->
                    niimbotPrinter = NiimbotPrinter(mContext, socket)
                    android.util.Log.d("NiimbotPrinter", "Starting printBitmap in coroutine (with mutex)")
                    GlobalScope.launch {
                        // Use mutex to ensure only one print job runs at a time
                        printMutex.withLock {
                            try {
                                android.util.Log.d("NiimbotPrinter", "Print mutex acquired, starting print")
                                // Always print quantity=1 to prevent multiple prints
                                niimbotPrinter.printBitmap(bitmap, density = density, labelType = labelType, quantity = 1, rotate = rotate, invertColor = invertColor)
                                android.util.Log.d("NiimbotPrinter", "printBitmap completed successfully")
                                result.success(true)
                            } catch (e: Exception) {
                                android.util.Log.e("NiimbotPrinter", "Print error: ${e.message}", e)
                                e.printStackTrace()
                                result.success(false)
                            } finally {
                                android.util.Log.d("NiimbotPrinter", "Print mutex released")
                            }
                        }
                    }
                } ?: run {
                    android.util.Log.e("NiimbotPrinter", "No Bluetooth socket available")
                    result.success(false)
                }
            } else {
                println("Datos de imagen inválidos o incompletos")
                println("bytes: $bytes")
                println("width: $width")
                println("height: $height")
                result.success(false)
            }

        } else if (call.method == "disconnect") {
            disconncet()
            result.success(true)
        } else {
            result.notImplemented()
        }
    }

    private fun dispositivosVinculados(): List<String> {

        val listItems: MutableList<String> = mutableListOf()

        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            //lblmsj.setText("Esta aplicacion necesita de un telefono con bluetooth")
        }
        //si no esta prendido
        if (bluetoothAdapter?.isEnabled == false) {
            //val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            //startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            //mensajeToast("Bluetooth off")
        }
        //buscar bluetooth
        //Log.d(TAG, "buscando dispositivos: ")
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        pairedDevices?.forEach { device ->
            val deviceName = device.name
            val deviceHardwareAddress = device.address
            listItems.add("$deviceName#$deviceHardwareAddress")
            //Log.d(TAG, "dispositivo: ${device.name}")
        }

        return listItems;
    }

    private suspend fun connect(): OutputStream? {
        //state = false
        return withContext(Dispatchers.IO) {
            var outputStream: OutputStream? = null
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                try {
                    val bluetoothAddress =
                        mac//"66:02:BD:06:18:7B" // replace with your device's address
                    val bluetoothDevice = bluetoothAdapter.getRemoteDevice(bluetoothAddress)
                    val bluetoothSocket = bluetoothDevice?.createRfcommSocketToServiceRecord(
                        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                    )
                    bluetoothAdapter.cancelDiscovery()
                    bluetoothSocket?.connect()
                    if (bluetoothSocket!!.isConnected) {
                        outputStream = bluetoothSocket!!.outputStream
                        state = true
                        //outputStream.write("\n".toByteArray())
                    } else {
                        state = false
                        Log.d(TAG, "Desconectado: ")
                    }
                    //bluetoothSocket?.close()
                } catch (e: Exception) {
                    state = false
                    var code: Int = e.hashCode() //1535159 apagado //
                    Log.d(TAG, "connect: ${e.message} code $code")
                    outputStream?.close()
                }
            } else {
                state = false
                Log.d(TAG, "Problema adapter: ")
            }
            outputStream
        }
    }

    private fun disconncet() {
        bluetoothSocket?.close()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}


