# DHU protocol audit

This change checks Gearslip's implemented projection channels against Android Studio's
Desktop Head Unit (DHU), rather than inferring the phone's behavior solely from aasdk.
It addresses the invalid audio and microphone setup messages visible in
[issue #18](https://github.com/Seb3thehacker/Gearslip/issues/18). The Chevrolet head unit's
25-second disconnect still needs a test in that vehicle; a DHU run cannot establish its cause.

## Reference and method

The reference is the stable `extras;google;auto` SDK package, revision **2.0**:

- [Official DHU documentation](https://developer.android.com/training/cars/testing/dhu).
- [Linux x64 archive](https://dl.google.com/android/repository/desktop-head-unit-linux-x64_r02.0.zip),
  SHA-1 `77e3f80c2834e1fad33f56539ceb0215da408fab` from Google's SDK repository manifest.
- [Mac ARM64 archive](https://dl.google.com/android/repository/desktop-head-unit-darwin-aarch64_r02.0.zip),
  SHA-1 `4e90abf932e512d6ca954b5126dd3a11e7a279e2` from the same manifest.
- Linux executable SHA-256: `a25708f2764398d3f14ee9411cb2d80954af84f7f18c0f750d4f4d1b5e3a7fb5`.
- Mac executable SHA-256: `4e42c054167d6a09a6cff04bba0b62d88898d39e866f241e7393750d2e23c163`.

The symbolized Linux ELF was imported, analyzed, and decompiled with Ghidra 12.1.4.
The native Mac DHU (`2022-03-30-438482292`, protocol 1.7) was also exercised over TCP.
The table below records independently implemented wire behavior, not copied decompiler output.
Addresses are Linux ELF virtual addresses; Ghidra's default import added `0x100000`.

| Area | Reference symbols / addresses | Result |
| --- | --- | --- |
| Version / TLS / discovery | `Controller::routeMessage` `0x733f80`, `sendVersionRequest` `0x734c20`, `handleVersionResponse` `0x734800` | DHU requests 1.7, then acts as TLS client. Gearslip acts as TLS server, waits for authentication, and requests discovery over encrypted frames. Negotiate supported 1.x minor versions instead of forcing the former certificate experiment's 1.3 response. |
| Framing | `ChannelManager::messageToFrames`, `encodeFrame` `0x737680`, `decodeFrame`, `framesToMessage` | Chunk plaintext before encrypting each frame. FIRST includes the total plaintext length. Channel control uses bit 2 and a complete frame. Validate lengths, fragment ordering, and consistent flags. The 16 MiB message / 32 MiB aggregate limits are local resource limits. |
| Channel opening / errors | `MessageRouter::routeChannelControlMsg` `0x73b630`, `routeMessage` `0x73b9b0` | Open requests/responses are channel-control messages. Empty message `0x00ff` reports an unexpected or unparseable message; it does not acknowledge successful setup. |
| Audio/video setup | `MediaSinkBase::routeMessage` `0x73abc0`, `gal::Setup::_InternalParse` `0x80b770`, `AudioSink::sendConfig` `0x732f00` | Setup field 1 is the codec enum (PCM=1, H.264=3). Config supplies readiness, the ACK limit, and configuration indices. Start supplies session and selected index. Stop has no fields. |
| Media flow control | `MediaSinkBase::ackFrames` `0x73b040`, `gal::Ack` | ACK field 1 identifies the session and field 2 counts packets. Reserve credit before enqueueing; reject stale/oversized ACKs. A timeout stops the stream instead of assuming the receiver acknowledged it. |
| Microphone | `AudioSource::routeMessage` `0x733a50`, `microphoneResponse` `0x733dc0`, `send` `0x733cd0` | Source channels accept microphone requests and ACKs, not Setup. Request fields 1/4 are open/max-unacked. Successful response fields 1/2 are status/session. Audio carries an eight-byte big-endian timestamp followed by PCM. |
| Input and sensors | `InputSource::routeMessage` `0x739610`, `SensorSource::routeMessage` `0x73fd40` | Existing binding/request IDs match. Accept packed or unpacked repeated numeric fields. Native DHU accepted key binding and location/night/driving subscriptions. |
| Navigation / playback metadata | `NavigationStatusEndpoint::routeMessage` `0x73c140`, `MediaPlaybackStatusEndpoint::routeMessage` `0x73a6b0`, corresponding `gal` parsers | Existing status/turn/distance and playback/metadata message IDs and wire fields match the inspected receiver. Native DHU accepted an inactive navigation status. |
| Heartbeat | `Controller::handlePingRequest` `0x734a00`, `GalService::PingService::heartBeat` `0x73d0f0` | Echo the complete 64-bit timestamp. Do not invent a timestamp for a malformed request. |

Direct native probes reproduced `0x00ff` for the old `Setup { 1: 0 }` on both PCM sinks
and the microphone. Changing the sink message to `08 01` produced Config
`08 02 10 80 20 18 00` (READY, 4096 credits, index 0). The microphone still rejected
Setup with codec 1, confirming that it needs its separate request/response exchange.

The audit covers the channels Gearslip implements. It does not establish support for every
optional service, newer protocol major versions, compressed audio, or every vehicle firmware.
Unsupported codecs stay closed instead of receiving PCM or H.264 in the wrong format.
Every video session waits for a keyframe before sending dependent frames. Requesting a sync
frame from Android's encoder is asynchronous, so a request alone cannot safely restart a decoder.

## Tests and native reproduction

The regression suite contains 79 tests covering framing, malformed input, media negotiation,
flow control, microphone responses, and session lifecycle races. Run it with an Android SDK
and JDK 21 configured:

```sh
./gradlew :gearslip:testDebugUnitTest :gearslip:assembleDebug
```

`DhuProtocolActivity` is a debug-only, shell-permission-protected endpoint. It binds to the
emulator's loopback address, sends the normal encoder's video test card and silent PCM for
90 seconds, then disconnects. It uses the production runner, TLS, framing, and media handlers.
No certificate or private key is included. Stage a separately supplied test identity without
changing the app's imported certificate or settings:

```sh
openssl pkcs12 -export -in test-cert.pem -inkey test-key.pem \
  -name phone -passout pass:test-only -out dhu-test.p12
adb -s emulator-5554 install -r build/Gearslip-debug.apk
adb -s emulator-5554 push dhu-test.p12 /data/local/tmp/gearslip-dhu-test.p12
adb -s emulator-5554 shell run-as app.seb3thehacker.gearslip.dev \
  cp /data/local/tmp/gearslip-dhu-test.p12 cache/dhu-test.p12
adb -s emulator-5554 forward tcp:5277 tcp:5277
adb -s emulator-5554 shell am start \
  -n app.seb3thehacker.gearslip.dev/app.seb3thehacker.gearslip.DhuProtocolActivity
/path/to/desktop-head-unit -h -a 127.0.0.1:5277 -c /path/to/headunit.ini
```

Use the intended emulator's serial in those commands. Enable `instrumentcluster`, touch,
and location/night/driving sensors in the DHU configuration to exercise those channels.
The DHU console also supports `tap`, `night`, `day`, and focus commands. A test certificate
must be accepted by that DHU; an arbitrary self-signed name is rejected by its identity check.
To repeat a run, finish or force-stop the debug test activity before starting it again.

The initial Pixel 9 emulator run lasted the full 90 seconds. Video ACKs and ping responses
continued past the reported 25-second failure point, and audio setup succeeded. The software
video test-card path was slow, and the DHU reported audio underruns; this is a protocol smoke
test, not an audio quality or frame-rate benchmark. Microphone response ordering, rejection,
PCM conversion, and ACK session selection are covered by unit tests; physical microphone
capture was not enabled in this run.

A final 55-second run exercised two native/projected focus cycles. Both video resumes
continued receiving ACKs, audio credit was released throughout, and the DHU logged no decoder
errors after the keyframe fix. The test ended by quitting the DHU, exercising EOF cleanup.

After testing, remove the forwarding rule and staged identity:

```sh
adb -s emulator-5554 forward --remove tcp:5277
adb -s emulator-5554 shell run-as app.seb3thehacker.gearslip.dev rm cache/dhu-test.p12
adb -s emulator-5554 shell rm /data/local/tmp/gearslip-dhu-test.p12
```
