import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart' as fbp;
import 'niimbot_ble_printer.dart';
import 'niimbot_method_channel_impl.dart';
import '../niimbot_label_printer.dart';

/// Platform-specific implementation:
/// - Android: Uses Kotlin code via method channels (Bluetooth Classic)
/// - iOS/Other: Uses BLE implementation
class NiimbotPrinterImpl {
  // Use method channel on Android, BLE on other platforms
  final bool _useMethodChannel = Platform.isAndroid;

  final NiimbotBlePrinter? _blePrinter =
      Platform.isAndroid ? null : NiimbotBlePrinter();
  final NiimbotMethodChannelImpl? _methodChannelImpl =
      Platform.isAndroid ? NiimbotMethodChannelImpl() : null;

  /// Check if Bluetooth is available
  Future<bool> bluetoothIsEnabled() async {
    if (_useMethodChannel) {
      return await _methodChannelImpl!.bluetoothIsEnabled();
    } else {
      return await _blePrinter!.isBluetoothAvailable();
    }
  }

  /// Check if connected
  Future<bool> isConnected() async {
    if (_useMethodChannel) {
      return await _methodChannelImpl!.isConnected();
    } else {
      return _blePrinter!.isConnected;
    }
  }

  /// Get paired devices (returns flutter_blue_plus BluetoothDevice)
  Future<List<fbp.BluetoothDevice>> getPairedDevices() async {
    if (_useMethodChannel) {
      return await _methodChannelImpl!.getPairedDevices();
    } else {
      return await _blePrinter!.getPairedDevices();
    }
  }

  /// Start scanning for devices (returns flutter_blue_plus BluetoothDevice)
  Stream<fbp.BluetoothDevice> startScan(
      {Duration timeout = const Duration(seconds: 10)}) {
    if (_useMethodChannel) {
      return _methodChannelImpl!.startScan(timeout: timeout);
    } else {
      return _blePrinter!.startScan(timeout: timeout);
    }
  }

  /// Connect to a device (accepts flutter_blue_plus BluetoothDevice)
  Future<bool> connect(fbp.BluetoothDevice device) async {
    if (_useMethodChannel) {
      return await _methodChannelImpl!.connect(device);
    } else {
      return await _blePrinter!.connect(device);
    }
  }

  /// Disconnect
  Future<void> disconnect() async {
    if (_useMethodChannel) {
      await _methodChannelImpl!.disconnect();
    } else {
      await _blePrinter!.disconnect();
    }
  }

  /// Send print data
  Future<bool> send(PrintData data) async {
    if (_useMethodChannel) {
      return await _methodChannelImpl!.send(data);
    } else {
      try {
        await _blePrinter!.printBitmap(
          imagePixels: Uint8List.fromList(data.data),
          width: data.width,
          height: data.height,
          density: data.density,
          labelType: data.labelType,
          quantity: 1,
          rotate: data.rotate,
          invertColor: data.invertColor,
        );
        return true;
      } catch (e) {
        debugPrint('[NiimbotPrinterImpl] Print error: $e');
        return false;
      }
    }
  }
}
