# HRMS

A Java 17+ HR management project. Its interactive browser dashboard is served by
Java's built-in `HttpServer`; the HRMS backend uses core Java and saves data locally
in `hrms-data.txt` in the current working directory. The dashboard supports employee
management, attendance, leave requests, and live summary cards without external
libraries or a framework.

## Start the web dashboard

Open a terminal in the project folder. On the first run, create an administrator
account when prompted. Then the app starts its local web server and opens the
dashboard in your browser; otherwise open `http://localhost:8080`.

```powershell
javac -d out src\main\java\hrms\Main.java src\main\java\hrms\WebDashboard.java
java -cp out hrms.Main
```

Keep the terminal open while using the dashboard. Press **Ctrl+C** in that terminal
to stop the local server. If port 8080 is already in use, choose another port:

```powershell
java -Dhrms.port=8081 -cp out hrms.Main
```

The HTML, styling, and browser interactions live in
`src/main/resources/hrms/dashboard.html`. They use browser-native HTML, CSS, and
JavaScript; no packages, CDNs, or UI frameworks are required.

To store HRMS data at a different path:

```powershell
java -Dhrms.data="C:\path\to\hrms-data.txt" -cp out hrms.Main
```

For the original console menu, run `java -cp out hrms.Main --console`.

## Features

- Add, view, update, delete, and search employee records.
- Record and review daily present, absent, and on-leave attendance.
- Create leave requests and approve or reject pending requests.
- Generate employee, attendance date-range, and leave status reports.
- Browse an interactive, responsive dashboard with live attendance and leave summaries.
- Persist records between runs and require administrator authentication.
- Bind the built-in HTTP server to the local machine only.

Passwords are stored as salted PBKDF2-HMAC-SHA256 hashes. The server is intended for
local project demonstrations, not deployment to a public network.
