#include <SoftwareSerial.h>

SoftwareSerial BTSerial(7, 8); // RX, TX

void setup() {
  Serial.begin(9600);
  BTSerial.begin(38400); // AT mode default baud rate

  Serial.println("=== HC-05 AT Mode Configurator ===");
  Serial.println("Testing AT mode detection...");
  delay(1000);

  // Check if module is in AT mode
  BTSerial.println("AT");
  delay(500);

  if (BTSerial.available()) {
    String response = "";
    while (BTSerial.available()) {
      response += (char)BTSerial.read();
    }
    response.trim();

    if (response == "OK") {
      Serial.println("[SUCCESS] AT mode detected! Response: " + response);
      Serial.println("Proceeding to change name...");
      delay(500);

      // Change the name
      BTSerial.println("AT+NAME=SmartCane");
      delay(500);

      if (BTSerial.available()) {
        String nameResp = "";
        while (BTSerial.available()) {
          nameResp += (char)BTSerial.read();
        }
        nameResp.trim();

        if (nameResp == "OK") {
          Serial.println("[SUCCESS] Name changed to 'SmartCane'!");
        } else {
          Serial.println("[FAIL] Name change failed. Response: " + nameResp);
        }
      } else {
        Serial.println("[FAIL] No response to name command.");
      }

    } else {
      Serial.println("[WARN] Got response but not OK: " + response);
      Serial.println("Module might not be in AT mode properly.");
    }

  } else {
    Serial.println("[FAIL] No response — module is NOT in AT mode.");
    Serial.println("Make sure EN/KEY pin is held HIGH before powering on.");
  }
}

void loop() {
  // Pass-through for manual AT commands via Serial Monitor
  if (BTSerial.available()) {
    Serial.write(BTSerial.read());
  }
  if (Serial.available()) {
    BTSerial.write(Serial.read());
  }
}