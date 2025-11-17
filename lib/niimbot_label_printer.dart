import 'dart:async';
import 'dart:typed_data';
import 'package:flutter_blue_plus/flutter_blue_plus.dart' as fbp;
import 'src/niimbot_printer_impl.dart';

/// Main class for Niimbot label printer using BLE
class NiimbotLabelPrinter {
  final NiimbotPrinterImpl _impl = NiimbotPrinterImpl();

  /// Check if Bluetooth is enabled
  Future<bool> bluetoothIsEnabled() async {
    return await _impl.bluetoothIsEnabled();
  }

  /// Check if connected to a printer
  Future<bool> isConnected() async {
    return await _impl.isConnected();
  }

  /// Get paired/bonded devices (returns flutter_blue_plus BluetoothDevice)
  Future<List<fbp.BluetoothDevice>> getPairedDevices() async {
    return await _impl.getPairedDevices();
  }

  /// Start scanning for BLE devices (returns flutter_blue_plus BluetoothDevice)
  Stream<fbp.BluetoothDevice> startScan(
      {Duration timeout = const Duration(seconds: 10)}) {
    return _impl.startScan(timeout: timeout);
  }

  /// Connect to a Bluetooth device (accepts flutter_blue_plus BluetoothDevice)
  Future<bool> connect(fbp.BluetoothDevice device) async {
    return await _impl.connect(device);
  }

  /// Disconnect from the printer
  Future<void> disconnect() async {
    await _impl.disconnect();
  }

  /// Send print data to the printer
  Future<bool> send(PrintData data) async {
    return await _impl.send(data);
  }
}

/// Compatibility wrapper for BluetoothDevice
/// Converts between flutter_blue_plus BluetoothDevice and the legacy format
class BluetoothDevice {
  late String name;
  late String address;

  BluetoothDevice({
    required this.name,
    required this.address,
  });

  BluetoothDevice.fromString(String string) {
    List<String> list = string.split('#');
    name = list[0];
    address = list[1];
  }

  BluetoothDevice.fromMap(Map<String, dynamic> map) {
    name = map['name'] ?? '';
    address = map['address'] ?? '';
  }

  /// Create from flutter_blue_plus BluetoothDevice
  BluetoothDevice.fromFbpDevice(fbp.BluetoothDevice device) {
    name = device.platformName.isNotEmpty ? device.platformName : 'Unknown';
    address = device.remoteId.str;
  }

  /// Convert to flutter_blue_plus BluetoothDevice
  /// Note: This requires finding the device from flutter_blue_plus
  /// Use getPairedDevices() or startScan() to get the actual fbp.BluetoothDevice
  Future<fbp.BluetoothDevice?> toFbpDevice() async {
    // Try to find in paired devices
    final paired = await fbp.FlutterBluePlus.bondedDevices;
    for (final device in paired) {
      if (device.remoteId.str == address || device.platformName == name) {
        return device;
      }
    }
    return null;
  }

  Map<String, dynamic> toMap() {
    return {
      'name': name,
      'address': address,
    };
  }
}

class PrintData {
  late List<int> data;
  late int width;
  late int height;
  late bool rotate;
  late bool invertColor;
  late int density;
  late int labelType;

  PrintData({
    required this.data,
    required this.width,
    required this.height,
    required this.rotate,
    required this.invertColor,
    required this.density,
    required this.labelType,
  });

  PrintData.fromMap(Map<String, dynamic> map) {
    data = map['bytes'];
    width = map['width'];
    height = map['height'];
    rotate = map['rotate'];
    invertColor = map['invertColor'];
    density = map['density'];
    labelType = map['labelType'];
  }

  Map<String, dynamic> toMap() {
    List<int> bytes = data;
    // Trasform bytes to Uint8List if necessary
    if (bytes.runtimeType == Uint8List) {
      bytes = bytes.toList();
    }
    return {
      'bytes': bytes,
      'width': width,
      'height': height,
      'rotate': rotate,
      'invertColor': invertColor,
      'density': density,
      'labelType': labelType,
    };
  }
}
