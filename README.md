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
<img width="507" height="575" alt="Screenshot 2026-09-30 170919" src="https://github.com/user-attachments/assets/9f3345cd-5338-4e5a-9db9-0414fd90d590" />
<img width="1896" height="911" alt="Screenshot 2026-09-30 171743" src="https://github.com/user-attachments/assets/15b7b95b-d67b-46d4-8955-296b75f0da73" />
<img width="1882" height="917" alt="Screenshot 2026-09-30 171843" src="https://github.com/user-attachments/assets/2ecb0bac-b09b-4498-8239-d82df293260b" />
<img width="1872" height="912" alt="Screenshot 2026-09-30 171942" src="https://github.com/user-attachments/assets/a4aff2ba-d39b-4b70-83fe-2d245a5cf65d" />
<img width="1855" height="897" alt="Screenshot 2026-09-30 172036" src="https://github.com/user-attachments/assets/78c63bc0-4047-437f-86c3-0e3e0cf4d693" />
<img width="1860" height="812" alt="Screenshot 2026-09-30 172127" src="https://github.com/user-attachments/assets/e8457726-0e40-48f3-afe6-755476aa434b" />
<img width="1512" height="637" alt="Screenshot 2026-09-30 172240" src="https://github.com/user-attachments/assets/bfecd67a-c857-45d8-8317-0f0e88ea4830" />
<img width="1570" height="645" alt="Screenshot 2026-09-30 172342" src="https://github.com/user-attachments/assets/cd152642-189e-46a9-bb11-2832ca06754f" />


## Author

**Soundarya**  
Information Science and Engineering Student
