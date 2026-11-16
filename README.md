# Peach TimeGuard
จำกัดเวลาใช้แอปบน Android TV ตั้งได้แยกรายวัน (จ-อา) หมดเวลาแล้วแอปถูกล็อก ต้องใส่ PIN จึงใช้ต่อได้

## Build เป็น APK (ไม่ต้องลง Android Studio)
1. สร้าง repo ใหม่บน GitHub แล้วอัปโหลดทุกไฟล์ในโฟลเดอร์นี้ (รวมโฟลเดอร์ .github)
2. แท็บ Actions → "Build APK" → รอ 3-5 นาที → ดาวน์โหลด artifact `PeachTimeGuard-apk`
3. แตกไฟล์จะได้ app-debug.apk

## ติดตั้งบนทีวี
- `adb connect <IP ทีวี>` แล้ว `adb install app-debug.apk`
- หรือเอาขึ้น Google Drive แล้วเปิดด้วยแอป Downloader / File Commander บนทีวี

## ตั้งค่าครั้งแรก
1. เปิดแอป Peach TimeGuard → กด "ตั้ง / เปลี่ยน PIN"
2. กด "เปิดบริการ (Accessibility)" → เปิด Peach TimeGuard
3. เลือกแอป (เช่น YouTube) → กดปุ่มวันเพื่อวนค่า: ไม่จำกัด / ห้ามใช้ / 15 / 30 / 45 / 60 / 90 / 120 / 180 นาที → บันทึก
