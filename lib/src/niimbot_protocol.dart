import 'dart:typed_data';

/// Niimbot printer protocol implementation
/// Based on the protocol used in the Android implementation
class NiimbotProtocol {
  /// Create a packet with the Niimbot protocol format:
  /// Header: 0x55 0x55
  /// Type: 1 byte
  /// Data length: 1 byte
  /// Data: variable length
  /// Checksum: 1 byte (type XOR length XOR all data bytes)
  /// Footer: 0xAA 0xAA
  static Uint8List createPacket(int type, Uint8List data) {
    final packet = Uint8List(7 + data.length);
    int offset = 0;

    // Header
    packet[offset++] = 0x55;
    packet[offset++] = 0x55;

    // Type
    packet[offset++] = type;

    // Data length
    packet[offset++] = data.length;

    // Data
    packet.setRange(offset, offset + data.length, data);
    offset += data.length;

    // Calculate checksum: type XOR length XOR all data bytes
    int checksum = type ^ data.length;
    for (int i = 0; i < data.length; i++) {
      checksum ^= data[i];
    }
    packet[offset++] = checksum;

    // Footer
    packet[offset++] = 0xAA;
    packet[offset++] = 0xAA;

    return packet;
  }

  /// Encode image bitmap data into line packets
  /// Each line is sent as a separate packet with command 0x85
  /// Converts RGBA image to 1-bit black/white bitmap (1 = black, 0 = white)
  static List<Uint8List> encodeImage(
    Uint8List imagePixels,
    int width,
    int height,
  ) {
    final List<Uint8List> packets = [];

    // Each line of the image
    for (int y = 0; y < height; y++) {
      // Calculate bytes needed for this line (1 bit per pixel)
      final lineDataLength = (width / 8).ceil();
      final lineData = Uint8List(lineDataLength);

      // Convert pixels to bits (1-bit black/white bitmap)
      for (int x = 0; x < width; x++) {
        final pixelIndex = (y * width + x) * 4; // RGBA format
        if (pixelIndex + 3 < imagePixels.length) {
          final r = imagePixels[pixelIndex];
          final g = imagePixels[pixelIndex + 1];
          final b = imagePixels[pixelIndex + 2];
          final a = imagePixels[pixelIndex + 3];

          // Calculate brightness using standard luminance formula
          final brightness = (r * 0.299 + g * 0.587 + b * 0.114).round();

          // Black pixel (bit = 1) if brightness < 128 and alpha >= 128
          // White pixel (bit = 0) if brightness >= 128 or alpha < 128
          // This creates a proper black/white bitmap for thermal printing
          if (a >= 128 && brightness < 128) {
            final byteIndex = x ~/ 8;
            final bitIndex = 7 - (x % 8); // MSB first
            lineData[byteIndex] |= (1 << bitIndex);
          }
        }
      }

      // Create header for this line: [y (2 bytes), 0, 0, 0, 1]
      final header = Uint8List(6);
      header[0] = (y & 0xFF);
      header[1] = ((y >> 8) & 0xFF);
      header[2] = 0;
      header[3] = 0;
      header[4] = 0;
      header[5] = 1;

      // Combine header and line data
      final packetData = Uint8List(header.length + lineData.length);
      packetData.setRange(0, header.length, header);
      packetData.setRange(
          header.length, header.length + lineData.length, lineData);

      // Create packet with command 0x85
      packets.add(createPacket(0x85, packetData));
    }

    return packets;
  }

  /// Command: Set label density (1-5)
  static Uint8List setLabelDensityCommand(int density) {
    if (density < 1 || density > 5) {
      throw ArgumentError('Density must be between 1 and 5');
    }
    return createPacket(0x21, Uint8List.fromList([density]));
  }

  /// Command: Set label type (1-3)
  static Uint8List setLabelTypeCommand(int labelType) {
    if (labelType < 1 || labelType > 3) {
      throw ArgumentError('Label type must be between 1 and 3');
    }
    return createPacket(0x23, Uint8List.fromList([labelType]));
  }

  /// Command: Start print job
  static Uint8List startPrintCommand() {
    return createPacket(0x01, Uint8List.fromList([1]));
  }

  /// Command: End print job
  static Uint8List endPrintCommand() {
    return createPacket(0xF3, Uint8List.fromList([1]));
  }

  /// Command: Start page print
  static Uint8List startPagePrintCommand() {
    return createPacket(0x03, Uint8List.fromList([1]));
  }

  /// Command: End page print
  static Uint8List endPagePrintCommand() {
    return createPacket(0xE3, Uint8List.fromList([1]));
  }

  /// Command: Set dimension (width, height in pixels)
  static Uint8List setDimensionCommand(int width, int height) {
    final data = Uint8List(4);
    data[0] = width & 0xFF;
    data[1] = (width >> 8) & 0xFF;
    data[2] = height & 0xFF;
    data[3] = (height >> 8) & 0xFF;
    return createPacket(0x13, data);
  }

  /// Command: Set quantity
  static Uint8List setQuantityCommand(int quantity) {
    final data = Uint8List(2);
    data[0] = quantity & 0xFF;
    data[1] = (quantity >> 8) & 0xFF;
    return createPacket(0x15, data);
  }

  /// Command: Get print status
  static Uint8List getPrintStatusCommand() {
    return createPacket(0xA3, Uint8List.fromList([1]));
  }

  /// Parse print status response
  static Map<String, int> parsePrintStatusResponse(Uint8List response) {
    if (response.length < 9) {
      throw ArgumentError('Invalid response length');
    }
    // Response format: [0x55, 0x55, type, length, data..., checksum, 0xAA, 0xAA]
    // Data starts at index 4, ends before checksum (length - 3)
    const dataStart = 4;
    final dataEnd = response.length - 3;
    if (dataEnd - dataStart < 4) {
      throw ArgumentError('Invalid status data length');
    }

    final data = response.sublist(dataStart, dataEnd);
    final page = (data[0] | (data[1] << 8)) & 0xFFFF;
    final progress1 = data[2] & 0xFF;
    final progress2 = data[3] & 0xFF;

    return {
      'page': page,
      'progress1': progress1,
      'progress2': progress2,
    };
  }

  /// Command: Heartbeat
  static Uint8List heartbeatCommand() {
    return createPacket(0xDC, Uint8List.fromList([1]));
  }

  /// Parse heartbeat response
  static Map<String, int?> parseHeartbeatResponse(Uint8List response) {
    if (response.length < 9) {
      return {
        'closing_state': null,
        'power_level': null,
        'paper_state': null,
        'rfid_read_state': null,
      };
    }

    const dataStart = 4;
    final dataEnd = response.length - 3;
    final data = response.sublist(dataStart, dataEnd);

    switch (data.length) {
      case 20:
        return {
          'closing_state': null,
          'power_level': null,
          'paper_state': data[18] & 0xFF,
          'rfid_read_state': data[19] & 0xFF,
        };
      case 13:
        return {
          'closing_state': data[9] & 0xFF,
          'power_level': data[10] & 0xFF,
          'paper_state': data[11] & 0xFF,
          'rfid_read_state': data[12] & 0xFF,
        };
      case 19:
        return {
          'closing_state': data[15] & 0xFF,
          'power_level': data[16] & 0xFF,
          'paper_state': data[17] & 0xFF,
          'rfid_read_state': data[18] & 0xFF,
        };
      case 10:
        return {
          'closing_state': data[8] & 0xFF,
          'power_level': data[9] & 0xFF,
          'paper_state': null,
          'rfid_read_state': data[8] & 0xFF,
        };
      case 9:
        return {
          'closing_state': data[8] & 0xFF,
          'power_level': null,
          'paper_state': null,
          'rfid_read_state': null,
        };
      default:
        return {
          'closing_state': null,
          'power_level': null,
          'paper_state': null,
          'rfid_read_state': null,
        };
    }
  }
}
