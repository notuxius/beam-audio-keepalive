package local.xreal.beamaudiokeepalive;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

final class GlassesCover {
    private static final String TAG = "BeamGlassesCover";
    private static final int COMMAND = 0x2B;
    private static final int USB_COVER = 4;
    private static final int DP_REQUEST = 5;
    private static final int DP_ACK = 6;
    private static final int TIMEOUT_MS = 300;

    static void acknowledge(Context context) {
        byte[] reply = exchange(context, USB_COVER, 0, true);
        if (reply != null) {
            Log.i(TAG, reply[27] == 1 ? "Glasses accepted the black cover"
                    : "Glasses reported no pending OLED hold");
        }
    }

    static Long pendingDp(Context context, boolean forceClaim) {
        byte[] reply = exchange(context, DP_REQUEST, 0, forceClaim);
        return reply == null ? null : pendingDpToken(reply, reply.length);
    }

    static void acknowledgeDp(Context context, int token, boolean forceClaim) {
        byte[] reply = exchange(context, DP_ACK, token, forceClaim);
        if (reply != null && reply[27] == 1) Log.i(TAG, "Glasses accepted the black cover before dropping USB");
    }

    private static byte[] exchange(Context context, int page, int token, boolean forceClaim) {
        UsbManager usb = context.getSystemService(UsbManager.class);
        for (UsbDevice device : usb.getDeviceList().values()) {
            if (device.getVendorId() != 0x3318 || device.getProductId() != 0x0432
                    || !"XREAL Air 2 Pro".equals(device.getProductName())) continue;
            if (!usb.hasPermission(device)) {
                if (page == USB_COVER) Log.i(TAG, "USB permission unavailable for cover handoff");
                return null;
            }
            try {
                return exchange(usb, device, page, token, forceClaim);
            } catch (RuntimeException error) {
                if (page != DP_REQUEST) Log.w(TAG, "Could not acknowledge the cover", error);
            }
            return null;
        }
        return null;
    }

    private static byte[] exchange(UsbManager usb, UsbDevice device, int page, int token, boolean forceClaim) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface control = device.getInterface(i);
            if (control.getId() != 4 || control.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int j = 0; j < control.getEndpointCount(); j++) {
                UsbEndpoint endpoint = control.getEndpoint(j);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) continue;
                if (endpoint.getAddress() == 0x86) in = endpoint;
                if (endpoint.getAddress() == 0x07) out = endpoint;
            }
            if (in == null || out == null) return null;
            UsbDeviceConnection connection = usb.openDevice(device);
            if (connection == null) return null;
            try {
                if (!connection.claimInterface(control, forceClaim)) return null;
                try {
                    byte[] packet = coverPacket(page, token);
                    if (connection.bulkTransfer(out, packet, packet.length, TIMEOUT_MS) != packet.length) return null;
                    byte[] response = new byte[64];
                    for (int attempt = 0; attempt < 4; attempt++) {
                        int length = connection.bulkTransfer(in, response, response.length, TIMEOUT_MS);
                        if (length < 0) break;
                        if (page == USB_COVER ? acknowledgementStatus(response, length) >= 0
                                : isDpResponse(response, length)) return response;
                    }
                    if (page != DP_REQUEST) Log.w(TAG, "No cover reply from the glasses");
                } finally {
                    connection.releaseInterface(control);
                }
            } finally {
                connection.close();
            }
            return null;
        }
        return null;
    }

    static int acknowledgementStatus(byte[] response, int length) {
        if (length < 28 || response[0] != (byte) 0xFD || response[15] != COMMAND || response[22] != 0
                || response[23] != 'B' || response[24] != 'H' || response[25] != 'A' || response[26] != '1') {
            return -1;
        }
        return response[27] == 1 ? 1 : 0;
    }

    static byte[] coverPacket() {
        return coverPacket(USB_COVER, 0);
    }

    static long pendingDpToken(byte[] response, int length) {
        if (!isDpResponse(response, length) || response[27] != 1) return -1;
        return Integer.toUnsignedLong(ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).getInt(28));
    }

    private static boolean isDpResponse(byte[] response, int length) {
        return length >= 32 && response[0] == (byte) 0xFD && response[15] == COMMAND && response[22] == 0
                && response[23] == 'B' && response[24] == 'H' && response[25] == 'D' && response[26] == '1';
    }

    static byte[] coverPacket(int page, int token) {
        byte[] packet = new byte[64];
        packet[0] = (byte) 0xFD;
        packet[5] = 20;
        packet[15] = COMMAND;
        packet[22] = (byte) 0xA7;
        packet[23] = (byte) 0xAD;
        packet[24] = (byte) page;
        ByteBuffer bytes = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(25, token);
        CRC32 crc = new CRC32();
        // The stock request CRC spans bytes 5 through 24. The ACK tick follows the page selector.
        if (page == DP_ACK) packet[5] = 24;
        crc.update(packet, 5, packet[5]);
        bytes.putInt(1, (int) crc.getValue());
        return packet;
    }
}
