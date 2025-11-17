import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart' as fbp;
import '../niimbot_label_printer.dart';

/// Android implementation using Kotlin code via method channels
class NiimbotMethodChannelImpl {
  static const MethodChannel _channel = MethodChannel('niimbot_label_printer');

  /// Check if Bluetooth is enabled
  Future<bool> bluetoothIsEnabled() async {
    try {
      final result = await _channel.invokeMethod<bool>('isBluetoothEnabled');
      return result ?? false;
    } catch (e) {
      debugPrint('[NiimbotMethodChannelImpl] Error checking Bluetooth: $e');
      return false;
    }
  }

  /// Check if connected
  Future<bool> isConnected() async {
    try {
      final result = await _channel.invokeMethod<bool>('isConnected');
      return result ?? false;
    } catch (e) {
      debugPrint('[NiimbotMethodChannelImpl] Error checking connection: $e');
      return false;
    }
  }

  /// Get paired devices (returns flutter_blue_plus BluetoothDevice for compatibility)
  /// Note: Method channel uses Classic Bluetooth, so we try to match with BLE devices
  /// If no BLE match is found, we still return devices that can be connected via method channel
  Future<List<fbp.BluetoothDevice>> getPairedDevices() async {
    try {
      final result =
          await _channel.invokeMethod<List<dynamic>>('getPairedDevices');
      if (result == null) return [];

      // Kotlin returns List<String> in format "name#address"
      final devices = <fbp.BluetoothDevice>[];
      final allBleDevices = await fbp.FlutterBluePlus.bondedDevices;

      for (final deviceString in result) {
        final parts = (deviceString as String).split('#');
        if (parts.length == 2) {
          final name = parts[0];
          final address = parts[1];

          // Try to find matching BLE device
          try {
            final matchingDevice = allBleDevices.firstWhere(
              (d) =>
                  d.remoteId.str == address ||
                  (d.platformName.isNotEmpty && d.platformName == name),
            );
            devices.add(matchingDevice);
          } catch (_) {
            // Device not found in BLE, but available via Classic Bluetooth
            // We can't return a BLE device, but the adapter can still connect using the address
            debugPrint(
                '[NiimbotMethodChannelImpl] Device $name ($address) available via Classic Bluetooth only');
            // Note: We skip these devices for now since we need BluetoothDevice objects
            // The adapter will need to handle this case
          }
        }
      }
      return devices;
    } catch (e) {
      debugPrint('[NiimbotMethodChannelImpl] Error getting paired devices: $e');
      return [];
    }
  }

  /// Start scanning - uses BLE scanning for discovery
  /// Method channel is used for connection/printing, but BLE is used for discovery
  /// since the printer supports both protocols
  Stream<fbp.BluetoothDevice> startScan(
      {Duration timeout = const Duration(seconds: 10)}) async* {
    // First yield paired devices immediately
    final pairedDevices = await getPairedDevices();
    final seenDevices = <String>{};

    for (final device in pairedDevices) {
      seenDevices.add(device.remoteId.str);
      yield device;
    }

    // Use BLE scanning to discover nearby devices
    // The printer supports BLE for discovery, even though we use Classic for printing
    final controller = StreamController<fbp.BluetoothDevice>();
    StreamSubscription<List<fbp.ScanResult>>? scanSubscription;
    Timer? timeoutTimer;

    try {
      // Start BLE scan
      await fbp.FlutterBluePlus.startScan(
        timeout: timeout,
        withServices: [],
      );

      // Set up timeout to close controller after scan completes
      timeoutTimer = Timer(timeout, () {
        controller.close();
      });

      // Listen to scan results (scanResults is Stream<List<ScanResult>>)
      scanSubscription = fbp.FlutterBluePlus.scanResults.listen(
        (results) {
          for (final result in results) {
            final device = result.device;
            if (!seenDevices.contains(device.remoteId.str)) {
              seenDevices.add(device.remoteId.str);
              if (!controller.isClosed) {
                controller.add(device);
              }
            }
          }
        },
        onError: (e) {
          debugPrint('[NiimbotMethodChannelImpl] Scan error: $e');
          if (!controller.isClosed) {
            controller.addError(e);
          }
        },
        onDone: () {
          // Scan completed, close controller
          if (!controller.isClosed) {
            controller.close();
          }
        },
      );

      // Yield devices from the controller until it closes
      await for (final device in controller.stream) {
        yield device;
      }
    } finally {
      // Stop scanning and cleanup
      timeoutTimer?.cancel();
      try {
        await fbp.FlutterBluePlus.stopScan();
      } catch (e) {
        debugPrint('[NiimbotMethodChannelImpl] Error stopping scan: $e');
      }
      await scanSubscription?.cancel();
      if (!controller.isClosed) {
        await controller.close();
      }
    }
  }

  /// Connect to a device using MAC address
  Future<bool> connect(fbp.BluetoothDevice device) async {
    try {
      final macAddress = device.remoteId.str;
      final result = await _channel.invokeMethod<bool>('connect', macAddress);
      return result ?? false;
    } catch (e) {
      debugPrint('[NiimbotMethodChannelImpl] Connection error: $e');
      return false;
    }
  }

  /// Disconnect
  Future<void> disconnect() async {
    try {
      await _channel.invokeMethod('disconnect');
    } catch (e) {
      debugPrint('[NiimbotMethodChannelImpl] Disconnect error: $e');
    }
  }

  /// Send print data
  Future<bool> send(PrintData data) async {
    try {
      debugPrint(
        '[NiimbotMethodChannelImpl] Sending print data: width=${data.width}, height=${data.height}, bytes=${data.data.length}, density=${data.density}, labelType=${data.labelType}, rotate=${data.rotate}',
      );
      final map = data.toMap();
      debugPrint(
        '[NiimbotMethodChannelImpl] Print data map keys: ${map.keys.join(", ")}',
      );
      debugPrint(
        '[NiimbotMethodChannelImpl] Bytes length in map: ${(map['bytes'] as List).length}',
      );
      final result = await _channel.invokeMethod<bool>('send', map);
      debugPrint('[NiimbotMethodChannelImpl] Print result: $result');
      return result ?? false;
    } catch (e, stackTrace) {
      debugPrint('[NiimbotMethodChannelImpl] Send error: $e');
      debugPrint('[NiimbotMethodChannelImpl] Stack trace: $stackTrace');
      return false;
    }
  }
}
