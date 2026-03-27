🚁 Drone Inventory Scanning App

A mobile application built using the DJI Mobile SDK that automates warehouse inventory scanning using a drone. The app enables efficient pallet and aisle scanning with predefined flight paths, real-time camera feed, and mission controls.

<img width="1125" height="627" alt="Screenshot 2026-03-26 181534" src="https://github.com/user-attachments/assets/b40f9f21-e964-46b8-a998-4c88b8b2e424" />
<img width="1126" height="630" alt="Screenshot 2026-03-26 181618" src="https://github.com/user-attachments/assets/560ab946-f281-406c-af67-41bbb9e1c864" />
<img width="1122" height="638" alt="Screenshot 2026-03-26 181603" src="https://github.com/user-attachments/assets/0c4cff74-5144-4f2d-a9a0-c6e97a5ebf43" />


📱 Features

📡 Drone Connection
Connect to DJI drone (Mavic 2 Pro supported)
Real-time connection status display

🧭 Automated Flight Missions
Scan entire aisles using predefined flight paths
Scan specific pallets by selecting aisle, row, and pallet

🎥 Live Camera Feed
Real-time video stream from the drone
Used for monitoring and future computer vision integration

🎮 Mission Controls
Start / Stop missions
Hover in place
Return to Home (RTH)
Emergency Land

🗺️ Custom Flight Logic
Zig-zag scanning pattern for aisle coverage
Adjustable height and movement control
Virtual stick control implementation

🏗️ Tech Stack
Language: Kotlin / Java
Framework: Android SDK
Drone SDK: DJI Mobile SDK (v4.18)
Architecture: Activity-based (with modular mission classes)

📂 Project Structure
app/
 ├── activities/
 │    ├── MainActivity
 │    ├── CustomMainActivity
 │    ├── DroneFeedActivityAisle
 │    ├── DroneFeedActivityPallet
 │    ├── ScanPalletActivity
 │
 ├── mission/
 │    ├── MissionStepAisle.kt
 │    ├── MissionStepPallet.kt
 │
 ├── ui/
 │    ├── activity_main.xml
 │    ├── activity_custom_main.xml
 │    ├── drone_feed_layout.xml
 │
 ├── sdk/
 │    ├── MainContent (handles DJI registration & connection)

 
🚀 How It Works
Launch the app
Connect to the drone
Click Open to access mission controls
Choose:
Scan Full Aisle → Executes zig-zag scan pattern
Scan Pallet → Select aisle + pallet → Fly to location
Monitor through live camera feed
Use control buttons as needed


⚙️ Setup Instructions
1. Clone the Repository
git clone https://github.com/yourusername/drone-inventory-app.git
2. Open in Android Studio
Open project
Sync Gradle
3. Add DJI SDK
Place DJI .aar file inside:
app/libs/
Update build.gradle:
implementation files('libs/dji-sdk.aar')
4. Permissions

Ensure the following permissions are enabled:

Location
Storage
Internet
USB / Drone connection


⚠️ Requirements
DJI Drone (Tested on Mavic 2 Pro)
Android device
DJI SDK configured
Developer account with DJI


🧠 Future Improvements
🤖 Object detection (YOLO / TensorFlow Lite)
📦 Automatic pallet recognition
☁️ Cloud inventory syncing
🗺️ 3D warehouse mapping
📊 Analytics dashboard
🛑 Safety Notes
Always operate drone in a safe environment
Test missions in open areas before warehouse deployment
Ensure emergency stop is functional before flight
👤 Author

McKay Fitzgerald
Computer Science Student
