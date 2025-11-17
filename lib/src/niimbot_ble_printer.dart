import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart' as fbp;
// Image processing removed - will handle in adapter layer
import 'niimbot_protocol.dart';

/// BLE service and characteristic UUIDs for Niimbot printers
/// These are standard BLE printer UUIDs - adjust if your printer uses different ones
class NiimbotBleUuids {
  // Standard BLE printer service UUID
  static const String serviceUuid = '0000ff00-0000-1000-8000-00805f9b34fb';

  // Write characteristic UUID
  static const String writeCharacteristicUuid =
      '0000ff01-0000-1000-8000-00805f9b34fb';

  // Notify/Read characteristic UUID
  static const String notifyCharacteristicUuid =
      '0000ff02-0000-1000-8000-00805f9b34fb';
}

/// Internal class for queued writes
class _QueuedWrite {
  final Uint8List data;
  final bool withoutResponse;
  final Completer<void> completer;

  _QueuedWrite(this.data, this.withoutResponse, this.completer);
}

/// Niimbot BLE printer implementation using flutter_blue_plus
class NiimbotBlePrinter {
  fbp.BluetoothDevice? _device;
  fbp.BluetoothCharacteristic? _writeCharacteristic;
  fbp.BluetoothCharacteristic? _notifyCharacteristic;
  StreamSubscription<List<int>>? _notifySubscription;
  bool _isConnected = false;

  // Write queue for serializing Bluetooth writes
  final _writeQueue = <_QueuedWrite>[];
  bool _isWriting = false;

  // Response handling
  final Map<int, Completer<Uint8List>> _pendingResponses = {};
  int _responseCounter = 0;

  /// Check if Bluetooth is available
  Future<bool> isBluetoothAvailable() async {
    try {
      final adapterState = await fbp.FlutterBluePlus.adapterState.first;
      return adapterState == fbp.BluetoothAdapterState.on;
    } catch (e) {
      debugPrint('[NiimbotBlePrinter] Error checking Bluetooth: $e');
      return false;
    }
  }

  /// Start scanning for BLE devices
  Stream<fbp.BluetoothDevice> startScan({
    Duration timeout = const Duration(seconds: 10),
  }) async* {
    try {
      // Check if Bluetooth is available
      if (!await isBluetoothAvailable()) {
        throw Exception('Bluetooth is not available');
      }

      // Start scan
      await fbp.FlutterBluePlus.startScan(
        timeout: timeout,
        withServices: [],
        androidUsesFineLocation: false,
      );

      debugPrint(
          '[NiimbotBlePrinter] Started BLE scan, listening for results...');

      // Track seen devices to avoid duplicates
      final seenDevices = <String>{};

      // Listen for scan results with timeout
      final scanResultsStream =
          fbp.FlutterBluePlus.scanResults.timeout(timeout, onTimeout: (sink) {
        debugPrint('[NiimbotBlePrinter] Scan timeout reached');
        sink.close();
      });

      await for (final result in scanResultsStream) {
        for (final scanResult in result) {
          final device = scanResult.device;
          final deviceId = device.remoteId.str;

          // Skip if we've already seen this device
          if (seenDevices.contains(deviceId)) {
            continue;
          }
          seenDevices.add(deviceId);

          final name = device.platformName;
          debugPrint(
            '[NiimbotBlePrinter] Found device: ${name.isEmpty ? "Unknown" : name} ($deviceId)',
          );

          // Yield all devices so user can see what's available
          // Common Niimbot printer names: D_*, B21_*, B1_*, B18_*, D11_*, INV_*, etc.
          // Filtering can be done at the UI level if needed
          yield device;
        }
      }

      debugPrint('[NiimbotBlePrinter] Scan stream completed');
    } catch (e) {
      debugPrint('[NiimbotBlePrinter] Error scanning: $e');
      rethrow;
    } finally {
      await fbp.FlutterBluePlus.stopScan();
      debugPrint('[NiimbotBlePrinter] Scan stopped');
    }
  }

  /// Get paired/bonded devices
  Future<List<fbp.BluetoothDevice>> getPairedDevices() async {
    try {
      final devices = await fbp.FlutterBluePlus.bondedDevices;
      return devices.where((device) {
        // Filter for Niimbot printers
        return device.platformName.isNotEmpty &&
            (device.platformName.startsWith('D_') ||
                device.platformName.startsWith('B21_') ||
                device.platformName.startsWith('B1_') ||
                device.platformName.startsWith('B18_') ||
                device.platformName.startsWith('D11_') ||
                device.platformName.contains('Niimbot'));
      }).toList();
    } catch (e) {
      debugPrint('[NiimbotBlePrinter] Error getting paired devices: $e');
      return [];
    }
  }

  /// Connect to a Bluetooth device
  Future<bool> connect(fbp.BluetoothDevice device) async {
    try {
      if (_isConnected && _device?.remoteId == device.remoteId) {
        debugPrint(
            '[NiimbotBlePrinter] Already connected to ${device.platformName}');
        return true;
      }

      // Disconnect if connected to a different device
      if (_isConnected) {
        await disconnect();
      }

      _device = device;
      debugPrint('[NiimbotBlePrinter] Connecting to ${device.platformName}...');

      // Connect to device
      await device.connect(
        timeout: const Duration(seconds: 15),
      );

      // Discover services
      final services = await device.discoverServices();
      debugPrint('[NiimbotBlePrinter] Discovered ${services.length} services');

      // Find the service and characteristics
      fbp.BluetoothService? targetService;
      for (final service in services) {
        // Try to find service by UUID or check all services
        if (service.uuid.toString().toLowerCase() ==
                NiimbotBleUuids.serviceUuid.toLowerCase() ||
            service.uuid.toString().toLowerCase().contains('ff00')) {
          targetService = service;
          break;
        }
      }

      // If not found by UUID, try to find by checking characteristics
      if (targetService == null) {
        for (final service in services) {
          for (final characteristic in service.characteristics) {
            if (characteristic.properties.write ||
                characteristic.properties.writeWithoutResponse) {
              targetService = service;
              break;
            }
          }
          if (targetService != null) break;
        }
      }

      if (targetService == null) {
        throw Exception('Could not find printer service');
      }

      // Find write characteristic
      _writeCharacteristic = targetService.characteristics.firstWhere(
        (c) =>
            (c.uuid.toString().toLowerCase() ==
                NiimbotBleUuids.writeCharacteristicUuid.toLowerCase()) ||
            (c.properties.write || c.properties.writeWithoutResponse),
        orElse: () => targetService!.characteristics.first,
      );

      // Find notify characteristic
      _notifyCharacteristic = targetService.characteristics.firstWhere(
        (c) =>
            (c.uuid.toString().toLowerCase() ==
                NiimbotBleUuids.notifyCharacteristicUuid.toLowerCase()) ||
            c.properties.notify,
        orElse: () => targetService!.characteristics.first,
      );

      // Request higher MTU for B21 Pro (supports up to 512 bytes)
      // This significantly improves print speed by allowing larger packets
      try {
        final requestedMtu = await device.requestMtu(512);
        debugPrint(
          '[NiimbotBlePrinter] MTU requested: 512, actual: $requestedMtu',
        );
      } catch (e) {
        debugPrint(
            '[NiimbotBlePrinter] MTU request failed (using default): $e');
      }

      // Enable notifications if available
      if (_notifyCharacteristic != null &&
          _notifyCharacteristic!.properties.notify) {
        await _notifyCharacteristic!.setNotifyValue(true);
        _notifySubscription = _notifyCharacteristic!.lastValueStream.listen(
          (value) {
            debugPrint(
                '[NiimbotBlePrinter] Received notification: ${value.length} bytes');
            if (value.isNotEmpty) {
              // Log first few bytes for debugging
              final preview = value.length > 10
                  ? value
                      .sublist(0, 10)
                      .map((b) => b.toRadixString(16).padLeft(2, '0'))
                      .join(' ')
                  : value
                      .map((b) => b.toRadixString(16).padLeft(2, '0'))
                      .join(' ');
              debugPrint(
                  '[NiimbotBlePrinter] Notification bytes: $preview${value.length > 10 ? '...' : ''}');

              // Complete the oldest pending response (FIFO)
              // Niimbot responses arrive in order, so match first pending request
              if (_pendingResponses.isNotEmpty) {
                final firstKey = _pendingResponses.keys.first;
                final completer = _pendingResponses.remove(firstKey);
                if (!completer!.isCompleted) {
                  debugPrint(
                      '[NiimbotBlePrinter] Matched response to pending request $firstKey');
                  completer.complete(Uint8List.fromList(value));
                } else {
                  debugPrint(
                      '[NiimbotBlePrinter] Completer already completed for request $firstKey');
                }
              } else {
                debugPrint(
                    '[NiimbotBlePrinter] Received notification but no pending requests');
              }
            }
          },
        );
      }

      _isConnected = true;
      debugPrint('[NiimbotBlePrinter] Connected successfully');
      return true;
    } catch (e) {
      debugPrint('[NiimbotBlePrinter] Connection error: $e');
      _isConnected = false;
      _device = null;
      _writeCharacteristic = null;
      _notifyCharacteristic = null;
      return false;
    }
  }

  /// Disconnect from the device
  Future<void> disconnect() async {
    try {
      _isWriting = false;
      _writeQueue.clear();
      _pendingResponses.clear();

      if (_notifySubscription != null) {
        await _notifySubscription!.cancel();
        _notifySubscription = null;
      }

      if (_notifyCharacteristic != null &&
          _notifyCharacteristic!.properties.notify) {
        try {
          await _notifyCharacteristic!.setNotifyValue(false);
        } catch (e) {
          debugPrint('[NiimbotBlePrinter] Error disabling notifications: $e');
        }
      }

      if (_device != null) {
        await _device!.disconnect();
      }

      _isConnected = false;
      _device = null;
      _writeCharacteristic = null;
      _notifyCharacteristic = null;
      debugPrint('[NiimbotBlePrinter] Disconnected');
    } catch (e) {
      debugPrint('[NiimbotBlePrinter] Disconnect error: $e');
    }
  }

  /// Check if connected
  bool get isConnected => _isConnected && _device != null;

  /// Process write queue
  Future<void> _processWriteQueue() async {
    if (_isWriting) return;
    if (_writeQueue.isEmpty) return;

    _isWriting = true;
    while (_writeQueue.isNotEmpty) {
      final queuedWrite = _writeQueue.removeAt(0);
      int retries = 3;
      bool success = false;

      while (retries > 0 && !success) {
        try {
          if (_writeCharacteristic == null) {
            throw Exception('Write characteristic not available');
          }

          await _writeCharacteristic!.write(
            queuedWrite.data,
            withoutResponse: queuedWrite.withoutResponse,
          );

          // Only delay for commands that need response (not for image packets)
          if (!queuedWrite.withoutResponse) {
            await Future.delayed(const Duration(milliseconds: 10));
          }
          success = true;
          queuedWrite.completer.complete();
        } catch (e) {
          retries--;
          if (retries > 0) {
            // Exponential backoff
            final delay = Duration(milliseconds: 50 * (4 - retries));
            await Future.delayed(delay);
          } else {
            debugPrint('[NiimbotBlePrinter] Write failed after retries: $e');
            queuedWrite.completer.completeError(e);
          }
        }
      }
    }
    _isWriting = false;

    // Process any new items that were added
    if (_writeQueue.isNotEmpty) {
      _processWriteQueue();
    }
  }

  /// Send command and wait for response
  Future<Uint8List?> sendCommand(
    Uint8List command, {
    bool waitForResponse = true,
    Duration timeout = const Duration(seconds: 2),
  }) async {
    if (!_isConnected || _writeCharacteristic == null) {
      throw Exception('Not connected to printer');
    }

    // Register response completer BEFORE sending command to avoid race condition
    Completer<Uint8List>? responseCompleter;
    int? requestId;
    if (waitForResponse) {
      responseCompleter = Completer<Uint8List>();
      requestId = _responseCounter++;
      _pendingResponses[requestId] = responseCompleter;
    }

    // Queue command write
    final writeCompleter = Completer<void>();
    _writeQueue.add(_QueuedWrite(command, false, writeCompleter));
    _processWriteQueue();
    await writeCompleter.future;

    if (!waitForResponse || responseCompleter == null) {
      return null;
    }

    try {
      final response = await responseCompleter.future.timeout(timeout);
      _pendingResponses.remove(requestId);
      return response;
    } catch (e) {
      _pendingResponses.remove(requestId);
      debugPrint('[NiimbotBlePrinter] Command response timeout or error: $e');
      // Return null on timeout - some commands may not respond
      return null;
    }
  }

  /// Send data without waiting for response (for image packets)
  Future<void> _sendDataWithoutResponse(Uint8List data) async {
    if (!_isConnected || _writeCharacteristic == null) {
      throw Exception('Not connected to printer');
    }

    final completer = Completer<void>();
    _writeQueue.add(_QueuedWrite(data, true, completer));
    _processWriteQueue();
    await completer.future;
  }

  /// Print bitmap image
  Future<void> printBitmap({
    required Uint8List imagePixels,
    required int width,
    required int height,
    int density = 4,
    int labelType = 1,
    int quantity = 1,
    bool rotate = false,
    bool invertColor = false,
  }) async {
    if (!_isConnected) {
      throw Exception('Not connected to printer');
    }

    debugPrint(
      '[NiimbotBlePrinter] ===== START PRINT JOB =====',
    );
    debugPrint(
      '[NiimbotBlePrinter] Image dimensions: ${width}x$height pixels',
    );
    debugPrint(
      '[NiimbotBlePrinter] Image data: ${imagePixels.length} bytes (RGBA)',
    );
    debugPrint(
      '[NiimbotBlePrinter] Print settings: density=$density, labelType=$labelType, quantity=$quantity',
    );
    debugPrint(
      '[NiimbotBlePrinter] Expected label size: ${(width * 25.4 / 203).toStringAsFixed(2)}mm x ${(height * 25.4 / 203).toStringAsFixed(2)}mm at 203 DPI',
    );

    // Image processing (rotation, inversion) should be done before calling this method
    // imagePixels should be in RGBA format
    // For now, we assume the image is already processed

    try {
      debugPrint('[NiimbotBlePrinter] Step 1: Setting label density=$density');
      await sendCommand(NiimbotProtocol.setLabelDensityCommand(density));

      debugPrint('[NiimbotBlePrinter] Step 2: Setting label type=$labelType');
      await sendCommand(NiimbotProtocol.setLabelTypeCommand(labelType));

      debugPrint('[NiimbotBlePrinter] Step 3: Starting print job');
      await sendCommand(NiimbotProtocol.startPrintCommand());

      debugPrint('[NiimbotBlePrinter] Step 4: Starting page print');
      await sendCommand(NiimbotProtocol.startPagePrintCommand());

      debugPrint(
        '[NiimbotBlePrinter] Step 5: Setting dimensions: width=$width, height=$height pixels',
      );
      await sendCommand(NiimbotProtocol.setDimensionCommand(width, height));

      debugPrint('[NiimbotBlePrinter] Step 6: Setting quantity=$quantity');
      await sendCommand(NiimbotProtocol.setQuantityCommand(quantity));

      // Encode and send image line by line
      debugPrint(
        '[NiimbotBlePrinter] Encoding image: ${width}x$height pixels, '
        '${imagePixels.length} RGBA bytes',
      );

      final imagePackets =
          NiimbotProtocol.encodeImage(imagePixels, width, height);
      debugPrint(
        '[NiimbotBlePrinter] Generated ${imagePackets.length} image packets, '
        'total bytes: ${imagePackets.fold<int>(0, (sum, p) => sum + p.length)}',
      );

      // Send packets as fast as possible (no delays for writeWithoutResponse)
      int packetIndex = 0;
      for (final packet in imagePackets) {
        await _sendDataWithoutResponse(packet);
        packetIndex++;

        // Log progress every 50 packets
        if (packetIndex % 50 == 0) {
          debugPrint(
            '[NiimbotBlePrinter] Sent $packetIndex/${imagePackets.length} packets',
          );
        }
      }

      debugPrint('[NiimbotBlePrinter] All image packets sent');

      // End page print
      debugPrint('[NiimbotBlePrinter] Step 7: Ending page print');
      await sendCommand(NiimbotProtocol.endPagePrintCommand());
      // Give printer a moment to process
      await Future.delayed(const Duration(milliseconds: 100));

      // Wait for print to complete
      debugPrint('[NiimbotBlePrinter] Step 8: Waiting for print completion');
      // For now, just wait a reasonable time for the printer to process
      // The printer should start printing immediately after receiving all data
      await Future.delayed(const Duration(milliseconds: 500));

      // Check status once to see if printing started
      try {
        final statusCmd = NiimbotProtocol.getPrintStatusCommand();
        final response =
            await sendCommand(statusCmd, timeout: const Duration(seconds: 1));
        if (response != null) {
          try {
            final status = NiimbotProtocol.parsePrintStatusResponse(response);
            final printedPages = status['page'] ?? 0;
            debugPrint(
              '[NiimbotBlePrinter] Print status: $printedPages/$quantity pages printed',
            );
          } catch (e) {
            debugPrint('[NiimbotBlePrinter] Error parsing status: $e');
          }
        }
      } catch (e) {
        debugPrint('[NiimbotBlePrinter] Status check failed: $e');
      }

      // End print
      debugPrint('[NiimbotBlePrinter] Step 9: Ending print job');
      await sendCommand(NiimbotProtocol.endPrintCommand());

      // Wait for all queued writes to complete
      debugPrint(
          '[NiimbotBlePrinter] Step 10: Waiting for write queue to empty');
      int queueWaitAttempts = 0;
      while (_writeQueue.isNotEmpty || _isWriting) {
        await Future.delayed(const Duration(milliseconds: 10));
        queueWaitAttempts++;
        if (queueWaitAttempts % 50 == 0) {
          debugPrint(
            '[NiimbotBlePrinter] Write queue: ${_writeQueue.length} items, isWriting: $_isWriting',
          );
        }
      }

      debugPrint('[NiimbotBlePrinter] ===== PRINT JOB COMPLETED =====');
    } catch (e) {
      debugPrint('[NiimbotBlePrinter] Print error: $e');
      rethrow;
    }
  }
}
