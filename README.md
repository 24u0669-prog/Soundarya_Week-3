# Human Resource Management System (HRMS)

A lightweight, browser-based human resource management system built with Java 17
and the JDK's built-in `HttpServer`. HRMS provides a simple way to manage employee
records, track attendance, and review leave requests through a responsive dashboard.
Application data is stored locally in `hrms-data.txt`; no external libraries or
frameworks are required.

## Features

- Create, view, update, delete, and search employee records.
- Record and review daily attendance, including present, absent, and on-leave statuses.
- Submit leave requests and approve or reject pending requests.
- Generate employee, attendance date-range, and leave-status reports.
- View live attendance and leave summaries on the interactive dashboard.
- Preserve records between runs using local file storage.
- Protect access with administrator authentication.
- Bind the web server to the local machine only.

## Requirements

- Java Development Kit (JDK) 17 or later
- A modern web browser

## Getting started

Open a terminal in the project directory and compile the application:

```powershell
javac -d out src\main\java\hrms\Main.java src\main\java\hrms\WebDashboard.java
```

Start HRMS:

```powershell
java -cp out hrms.Main
```

On the first run, follow the prompt to create an administrator account. The
dashboard opens in your browser. If it does not, navigate to
`http://localhost:8080`.

Keep the terminal open while using the dashboard. Press **Ctrl+C** in the
terminal to stop the server.

## Configuration

If port `8080` is already in use, start the application on another port:

```powershell
java -Dhrms.port=8081 -cp out hrms.Main
```

To save application data at a different location:

```powershell
java -Dhrms.data="C:\path\to\hrms-data.txt" -cp out hrms.Main
```

To use the original console menu instead of the web dashboard:

```powershell
java -cp out hrms.Main --console
```

## Project structure

- `src/main/java/hrms/Main.java` — application entry point and HRMS operations.
- `src/main/java/hrms/WebDashboard.java` — local HTTP server and dashboard integration.
- `src/main/resources/hrms/dashboard.html` — dashboard markup, styles, and browser interactions.
- `hrms-data.txt` — local application data, created or updated when the application runs.

The dashboard uses browser-native HTML, CSS, and JavaScript. It does not load
third-party packages, CDNs, or UI frameworks.

## Security and deployment

Administrator passwords are stored as salted PBKDF2-HMAC-SHA256 hashes. The
built-in server listens on the local machine and this project is intended for
local demonstrations, not public-network deployment.

## Author

**Soundarya*
Information Science Engineering Student 
