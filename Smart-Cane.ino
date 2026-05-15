// ============================================================
// Smart Blind Assistance Cane System
// Arduino UNO — ZS-042 (DS3231) Version
// ============================================================

#include <SoftwareSerial.h>
#include <DHT.h>
#include <RTClib.h>

// ── PIN DEFINITIONS ─────────────────────────────────────────
#define TRIG_PIN      2
#define ECHO_PIN      3
#define SOS_BTN_PIN   4
#define DHT_PIN       5
#define BUZZER_PIN    9
#define MOTOR_PIN     10
#define BT_TX         12
#define BT_RX         11
#define LED_RED       13
#define SMOKE_PIN     A0
#define LED_GREEN     A1
#define LED_YELLOW    A2

// ── THRESHOLDS ───────────────────────────────────────────────
#define DIST_WARNING      80
#define DIST_DANGER       30
#define SMOKE_THRESHOLD   400
#define TEMP_THRESHOLD    45

// ── OBJECTS ──────────────────────────────────────────────────
DHT dht(DHT_PIN, DHT11);
RTC_DS3231 rtc; //I2C: A4=SDA, A5=SCL
SoftwareSerial bluetooth(BT_RX, BT_TX);

// ── TIMING ───────────────────────────────────────────────────
unsigned long lastSensorRead  = 0;
unsigned long lastBTSend      = 0;
const int SENSOR_INTERVAL     = 50; // Read sensors every 50ms
const int BT_INTERVAL         = 500; // Send Bluetooth data every 500ms

// ── SOS BUTTON STATE ─────────────────────────────────────────
bool lastBtnState              = HIGH;
unsigned long lastDebounceTime = 0;
const int DEBOUNCE_DELAY       = 200; // Ignore re-triggers within 200ms

// ── SETUP ────────────────────────────────────────────────────
void setup() {
  Serial.begin(9600);
  bluetooth.begin(9600);

  pinMode(TRIG_PIN,    OUTPUT);
  pinMode(ECHO_PIN,    INPUT);
  pinMode(BUZZER_PIN,  OUTPUT);
  pinMode(MOTOR_PIN,   OUTPUT);
  pinMode(LED_RED,     OUTPUT);
  pinMode(LED_GREEN,   OUTPUT);
  pinMode(LED_YELLOW,  OUTPUT);
  pinMode(SOS_BTN_PIN, INPUT_PULLUP); // internal pull-up, no resistor needed

  dht.begin();

  if (!rtc.begin()) {
    Serial.println("[ERROR] RTC not found. Check SDA/SCL wiring.");
    while (1); // Halt forever — can't run without the clock
  }

  if (rtc.lostPower()) {
    Serial.println("[RTC] Lost power — setting time to compile time.");
    rtc.adjust(DateTime(F(__DATE__), F(__TIME__)));
  }

  Serial.println("==============================================");
  Serial.println("  Smart Blind Cane System — READY");
  Serial.println("  Waiting 2s for sensor warm-up...");
  Serial.println("==============================================");
  delay(2000);
  Serial.println("  System active. Monitoring started.");
  Serial.println("  SOS Button: Pin 4 active (INPUT_PULLUP)");
  Serial.println("==============================================");
}

// ── MAIN LOOP ────────────────────────────────────────────────
void loop() {
  unsigned long now = millis(); // Current time in milliseconds since boot

  // ── SOS Button Check (runs every loop, not rate-limited) ──
  bool currentBtnState = digitalRead(SOS_BTN_PIN);

  if (currentBtnState == LOW && lastBtnState == HIGH) { 
    if (now - lastDebounceTime > DEBOUNCE_DELAY) { //Only act if 200ms has passed since the last trigger
      lastDebounceTime = now;
      triggerSOS();
    }
  }
  lastBtnState = currentBtnState;

  // ── Sensor Block ──────────────────────────────────────────
  if (now - lastSensorRead >= SENSOR_INTERVAL) { //Only read sensors every 50ms.
    lastSensorRead = now;

    long  distance    = readUltrasonic();
    int   smokeLevel  = analogRead(SMOKE_PIN);
    float temperature = dht.readTemperature();
    float humidity    = dht.readHumidity();

    if (smokeLevel > SMOKE_THRESHOLD || temperature > TEMP_THRESHOLD) {
      setLEDs(true, false, false);
      alertPattern3_SmokeTemp();
      logHazard("SMOKE/TEMP", distance, smokeLevel, temperature);
    }
    else if (distance > 0 && distance < DIST_DANGER) {
      setLEDs(true, false, false);
      alertPattern1_Obstacle(distance, true);
      Serial.print("[ALERT] OBSTACLE DANGER | D:");
      Serial.print(distance);
      Serial.println("cm");
    }
    else if (distance > 0 && distance < DIST_WARNING) {
      setLEDs(false, false, true);
      alertPattern1_Obstacle(distance, false);
      Serial.print("[ALERT] OBSTACLE WARNING | D:");
      Serial.print(distance);
      Serial.println("cm");
    }
    else {
      setLEDs(false, true, false);
      noTone(BUZZER_PIN);
      digitalWrite(MOTOR_PIN, LOW);

      if (now - lastBTSend >= BT_INTERVAL) { 
        Serial.print("[STATUS] ALL CLEAR | D:");
        Serial.print(distance);
        Serial.print("cm | T:");
        Serial.print(temperature, 1);
        Serial.print("C | H:");
        Serial.print(humidity, 1);
        Serial.print("% | Smoke:");
        Serial.println(smokeLevel);
      }
    }

    if (now - lastBTSend >= BT_INTERVAL) { //sensor data is streamed to the phone every 500ms.
      lastBTSend = now;
      sendBluetooth(distance, smokeLevel, temperature, humidity);
    }
  }
}

// ── SOS TRIGGER ──────────────────────────────────────────────
void triggerSOS() {
  DateTime now = rtc.now();
  String timestamp = pad(now.hour()) + ":" + pad(now.minute()) + ":" + pad(now.second());

  Serial.println("==============================================");
  Serial.println("  *** SOS TRIGGERED ***");
  Serial.print  ("  Time     : ");
  Serial.println(timestamp);
  Serial.println("  Source   : Panic Button (Pin 4)");
  Serial.println("  Action   : BT alert sent to mobile app");
  Serial.println("  Status   : USER NEEDS RESCUE");
  Serial.println("==============================================");

  // Flash ALL LEDs 3 times + triple beep + motor pulse
  for (int i = 0; i < 3; i++) {
    setLEDs(true, true, true);
    tone(BUZZER_PIN, 1500, 150);
    digitalWrite(MOTOR_PIN, HIGH);
    delay(200);
    setLEDs(false, false, false);
    noTone(BUZZER_PIN);
    digitalWrite(MOTOR_PIN, LOW);
    delay(200);
  }

  // Send SOS over Bluetooth
  String msg = "SOS|PANIC|" + timestamp + "|USER_NEEDS_RESCUE";
  bluetooth.println(msg);
}

// ── ULTRASONIC ───────────────────────────────────────────────
long readUltrasonic() {
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);
  long duration = pulseIn(ECHO_PIN, HIGH, 30000); // Wait for echo; timeout at 30ms
  long distance = duration * 0.034 / 2;
  return distance;
}

// ── ALERT PATTERN 1: OBSTACLE ────────────────────────────────
void alertPattern1_Obstacle(long dist, bool danger) {
  int beepDelay = map(dist, 0, DIST_WARNING, 50, 400); //So closer = shorter gaps = faster beeping
  tone(BUZZER_PIN, danger ? 1000 : 700, 80); //Higher pitch for danger
  digitalWrite(MOTOR_PIN, danger ? HIGH : LOW); // Motor only in danger zone
  delay(beepDelay);
  noTone(BUZZER_PIN);
}

// ── ALERT PATTERN 2: SMOKE / TEMPERATURE ─────────────────────
void alertPattern3_SmokeTemp() {
  digitalWrite(MOTOR_PIN, HIGH);
  tone(BUZZER_PIN, 2000, 600); // High-pitched 600ms alarm
  delay(700);
  noTone(BUZZER_PIN);
  delay(100);
  digitalWrite(MOTOR_PIN, LOW);
}

// ── LED CONTROL ──────────────────────────────────────────────
void setLEDs(bool red, bool green, bool yellow) {
  digitalWrite(LED_RED,    red    ? HIGH : LOW);
  digitalWrite(LED_GREEN,  green  ? HIGH : LOW);
  digitalWrite(LED_YELLOW, yellow ? HIGH : LOW);
}

// ── LOG HAZARD ───────────────────────────────────────────────
void logHazard(String type, long dist, int smoke, float temp) {
  DateTime now = rtc.now();
  String timestamp = pad(now.hour()) + ":" + pad(now.minute()) + ":" + pad(now.second());

  Serial.print("[HAZARD] ");
  Serial.print(type);
  Serial.print(" | Time:");
  Serial.print(timestamp);
  Serial.print(" | D:");
  Serial.print(dist);
  Serial.print("cm | Smoke:");
  Serial.print(smoke);
  Serial.print(" | Temp:");
  Serial.print(temp, 1);
  Serial.println("C");

  String log = "LOG|" + type + "|" + timestamp + "|" + String(dist) + "cm";
  bluetooth.println(log);
}

// ── SEND DATA VIA BLUETOOTH ───────────────────────────────────
void sendBluetooth(long dist, int smoke, float temp, float hum) {
  String data = "DATA|";
  data += "D:" + String(dist) + "|";
  data += "S:" + String(smoke) + "|";
  data += "T:" + String(temp, 1) + "|";
  data += "H:" + String(hum, 1);
  bluetooth.println(data);
}

// ── HELPER: ZERO PAD ─────────────────────────────────────────
String pad(int n) {
  return (n < 10 ? "0" : "") + String(n);
}