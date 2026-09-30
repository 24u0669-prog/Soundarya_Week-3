# Human Resource Management System (HRMS)

A Java 25 HRMS application with a browser-based dashboard for managing employee
records, attendance, leave requests, and HR reports.

The application uses the JDK's built-in HTTP server and standard Java APIs. It
has no third-party runtime dependencies, frontend framework, or separate
frontend build.

## Features

- **Dashboard:** View employee and department totals, today's attendance, and
  pending leave requests.
- **Employee directory:** Add, update, view, and remove employee records.
- **Attendance:** Record employee attendance and review records by date.
- **Leave management:** Submit, review, approve, and reject leave requests.
- **Reports:** Review employee, attendance, and leave summaries.
- **Search:** Find employees by name, employee ID, or department.
- **Administrator access:** Set up an administrator on first launch and sign in
  on subsequent visits.
- **Local persistence:** Save HRMS data to a local file between runs.

## Requirements

- JDK 25
- Apache Maven 3.9 or newer
- A modern web browser

## Build

From the project root, run:

```powershell
mvn clean package
```

The executable JAR is created at `target/hrms-1.0.0.jar`.

## Run

```powershell
java -jar target/hrms-1.0.0.jar
```

The application prints its URL and opens the default browser when possible.
Otherwise, open [http://127.0.0.1:8080/](http://127.0.0.1:8080/) manually. On
first launch, create the administrator account in the browser. Use that
account to sign in on later launches.

To choose a different data file or port:

```powershell
java -Dhrms.data="C:\path\to\hrms-data.txt" -Dhrms.port=8080 -jar target\hrms-1.0.0.jar
```

Stop the application with **Ctrl+C** in the terminal.

## Data and security

By default, application data is stored in `hrms-data.txt` in the current
working directory. Keep this file safe: it contains HR records and administrator
credential hashes. Passwords are stored as salted hashes.

The HTTP server binds only to `127.0.0.1`, so the dashboard is intended for use
on the same computer where the application is running. Run it in a terminal
rather than an IDE output panel so process output and shutdown are easy to
manage.

## Project structure

```text
src/main/java/hrms/
  Main.java              Application entry point and HRMS data persistence
  BrowserDashboard.java  Local HTTP server and browser dashboard
pom.xml                  Java 25 Maven build configuration
hrms-data.txt            Local application data (created on first run)
```
## Dashboard Screenshots
<img width="507" height="575" alt="Screenshot 2026-09-30 170919" src="https://github.com/user-attachments/assets/10029cdc-011b-4ec7-a88a-bda07393d6bd" />


## Author

**Soundarya**  
Information Science and Engineering Student
