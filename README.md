# Human Resource Management System (HRMS)

A Java 17 desktop application for managing employees, attendance, and leave
requests. HRMS uses Java Swing for its graphical dashboard and saves application
data locally in `hrms-data.txt`. It does not require a web browser, web server,
or third-party libraries.

## Features

- Administrator setup and sign-in with salted PBKDF2-HMAC-SHA256 password hashes.
- Add, search, update, and delete employee records.
- Record attendance and view daily attendance information.
- Submit, approve, and reject leave requests.
- View overview cards and pending leave requests.
- Generate employee directory, attendance date-range, and leave summary reports.
- Persist records locally between runs.
- Optional console interface.

## Requirements

- Java Development Kit (JDK) 17 or later
- Windows, macOS, or Linux desktop environment

## Run the desktop application

Open a terminal in the project folder and compile the application:

```powershell
javac -d out src\main\java\hrms\Main.java src\main\java\hrms\DesktopDashboard.java
```

Start HRMS:

```powershell
java -cp out hrms.Main
```

On first launch, create an administrator account and sign in to open the Swing
dashboard. Administrator passwords must be at least 10 characters.

## Console mode

To use the console menu instead of the desktop dashboard:

```powershell
java -cp out hrms.Main --console
```

## Data location

By default, HRMS creates or updates `hrms-data.txt` in the current working
directory. Set a different data-file path with the `hrms.data` system property:

```powershell
java "-Dhrms.data=C:\path\to\hrms-data.txt" -cp out hrms.Main
```

## Project structure

- `src/main/java/hrms/Main.java` — application entry point, domain records, persistence, and console mode.
- `src/main/java/hrms/DesktopDashboard.java` — Java Swing dashboard and HR management screens.
- `hrms-data.txt` — local application data, created or updated when the application runs.

## Author

**Soundarya**  
Information Science Engineering Student
