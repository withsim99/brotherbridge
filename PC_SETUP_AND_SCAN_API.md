# PC Wireless Printing & HTTP Scan API Guide

Make sure the Android host phone (with the printer connected via USB OTG) and your PC are on the **same Wi-Fi network**, and **Remote Server** is running in the app. You can find the phone's IP address and port (default **`8631`**) on the app's **Remote Server** screen—or open `http://<phone-ip>:8631/` in any PC browser to confirm connectivity.

---

## 1. Using the HTTP Scan API (`/api/scan`) from a PC

The scanner uses a 5-step asynchronous HTTP/JSON workflow (no eSCL required). Pick any unique client ID for your PC (3–64 letters, numbers, `_`, or `-`, e.g., `my_pc_01`).

### Step 1: Check Scanner Capabilities (Optional)
- **Endpoint:** `GET http://<phone-ip>:8631/api/scan/capabilities`
- **Response:** JSON containing `available`, `busy`, `supportsFlatbed`, `supportsAdf`, `supportsAdfDuplex`, and `model`.

### Step 2: Start a Scan Job
- **Endpoint:** `POST http://<phone-ip>:8631/api/scan/start`
- **Header:** `Content-Type: application/json`
- **Body:**
```json
{
  "deviceId": "my_pc_01",
  "clientName": "My PC",
  "source": "FLATBED",
  "colorMode": "COLOR",
  "dpi": 300,
  "pageSize": "A4",
  "brightness": 50,
  "contrast": 50
}
```
- **Valid parameter values:**
  - `source`: `FLATBED`, `ADF_SIMPLEX`, `ADF_DUPLEX`
  - `colorMode`: `COLOR`, `GRAYSCALE`, `MONOCHROME`
  - `dpi`: `150`, `300`, `600`, `1200`, `2400`
  - `pageSize`: `A4`, `LETTER`, `A5`, `ID_CARD`
  - `brightness` / `contrast`: `0`–`100` (default `50`)
- **Response:** Returns **HTTP 202** immediately with a `jobId` (automatically queued if the printer/scanner is currently busy).

### Step 3: Poll Scan Progress (~every 2 seconds)
- **Endpoint:** `GET http://<phone-ip>:8631/api/scan/jobs/<jobId>/progress?deviceId=my_pc_01`
- **Status lifecycle:** `QUEUED` → `RUNNING` → `READY` (or `FAILED` / `CANCELED`).
- When `status` becomes `READY`, the `pages` array lists each page's `index` (starting at `1`), `fileName`, `sizeBytes`, `sha256`, `width`, and `height`.

### Step 4: Download Each Scanned Page (JPEG)
- **Endpoint:** `GET http://<phone-ip>:8631/api/scan/jobs/<jobId>/page/<index>?deviceId=my_pc_01`
- Returns the scanned page as `image/jpeg`.
- Response headers include `X-Scan-Sha256` and `X-Scan-Size-Bytes` for integrity verification.

### Step 5: Acknowledge Receipt (Cleanup Host Copy)
- **Endpoint:** `POST http://<phone-ip>:8631/api/scan/jobs/<jobId>/ack`
- **Header:** `Content-Type: application/json`
- **Body:**
```json
{
  "deviceId": "my_pc_01",
  "pageIndex": 1,
  "sha256": "<64-char-lowercase-sha256-of-downloaded-file>",
  "receivedBytes": 123456
}
```
- The host keeps the scanned page safely in its vault until your PC verifies and acknowledges it.

### Additional Endpoints
- **Recover un-acked scans after disconnect:** `GET http://<phone-ip>:8631/api/scan/pending?deviceId=my_pc_01`
- **Cancel an active or queued scan:** `POST http://<phone-ip>:8631/api/scan/jobs/<jobId>/cancel`

---

## 2. Adding the Printer Wirelessly on a PC

The app runs an **IPP 1.1/2.0 print server** at `http://<phone-ip>:8631/ipp/print` (accepting `application/pdf`, `image/jpeg`, `image/png`, and `image/webp`) and advertises itself on the LAN via **Bonjour / mDNS (`_ipp._tcp`)**.

### Windows 10 / 11
- **Automatic (recommended):**
  1. Open **Settings → Bluetooth & devices → Printers & scanners → Add device**.
  2. Wait a few seconds for the BrotherBridge printer to appear via Bonjour/mDNS, then click **Add device**.
- **Manual (by IP address):**
  1. Open **Printers & scanners → Add device → Add manually**.
  2. Select **Select a shared printer by name** and enter:
     `http://<phone-ip>:8631/ipp/print`
  3. Click **Next**. If prompted for a driver, choose **Microsoft → Microsoft IPP Class Driver**.
  4. Finish setup and print a test page.

### macOS
1. Open **System Settings → Printers & Scanners → Add Printer, Scanner, or Fax**.
2. **Automatic:** Select the BrotherBridge printer under the **Default** (Bonjour) tab, keep **Use: AirPrint / Generic PDF Printer**, and click **Add**.
3. **Manual (IP tab):**
   - **Protocol:** `Internet Printing Protocol - IPP`
   - **Address:** `<phone-ip>:8631`
   - **Queue:** `ipp/print`
   - **Use:** `Generic PDF Printer` → click **Add**.

### Linux (CUPS)
- **Automatic:** Open **Settings → Printers → Add** (CUPS/Avahi discovers `_ipp._tcp` automatically).
- **Manual (CLI or `http://localhost:631`):**
```bash
lpadmin -p BrotherBridge -E -v ipp://<phone-ip>:8631/ipp/print -m everywhere
```
