package hrms;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static hrms.Main.Attendance;
import static hrms.Main.AttendanceStatus;
import static hrms.Main.Employee;
import static hrms.Main.LeaveRequest;
import static hrms.Main.LeaveStatus;

final class BrowserDashboard {
    private static final String SESSION_COOKIE = "HRMS_SESSION";
    private static final int MAX_REQUEST_BYTES = 16_384;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, WebSession> SESSIONS = new ConcurrentHashMap<>();
    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);

    private final Main.Store store;
    private final HttpServer server;

    private BrowserDashboard(Main.Store store, HttpServer server) {
        this.store = store;
        this.server = server;
    }

    static void start(Main.Store store) throws IOException {
        int port;
        try {
            port = Integer.parseInt(System.getProperty("hrms.port", "8080"));
        } catch (NumberFormatException e) {
            throw new IOException("hrms.port must be a valid port number.", e);
        }
        if (port < 0 || port > 65535) {
            throw new IOException("hrms.port must be between 0 and 65535.");
        }

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        BrowserDashboard dashboard = new BrowserDashboard(store, server);
        server.createContext("/", dashboard::handle);
        server.setExecutor(Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "hrms-browser");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();

        int actualPort = server.getAddress().getPort();
        String address = "http://127.0.0.1:" + actualPort + "/";
        System.out.println("HRMS browser dashboard is running at " + address);
        System.out.println("This application listens only on this computer.");
        if (Boolean.parseBoolean(System.getProperty("hrms.browser.open", "true"))
                && Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().browse(URI.create(address));
            } catch (IOException | UnsupportedOperationException e) {
                System.err.println("Open the HRMS dashboard in your browser: " + address
                        + " (" + e.getMessage() + ")");
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "hrms-shutdown"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (IllegalArgumentException e) {
                sendHtml(exchange, 400, renderError(e.getMessage()));
            } catch (IOException e) {
                System.err.println("HRMS request failed: " + e.getMessage());
                sendHtml(exchange, 500, renderError("The request could not be completed. "
                        + e.getMessage()));
            } catch (RuntimeException e) {
                System.err.println("Unexpected HRMS request error: " + e);
                sendHtml(exchange, 500, renderError("An unexpected error occurred."));
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());

        if (path.equals("/favicon.ico")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        WebSession session = session(exchange, method.equals("GET"));
        if (path.equals("/login") && method.equals("GET")) {
            sendHtml(exchange, 200, renderLogin(session, null));
            return;
        }
        if (path.equals("/setup") && method.equals("GET")) {
            sendHtml(exchange, 200, renderSetup(session, null));
            return;
        }
        if (!method.equals("GET") && !method.equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
            sendHtml(exchange, 405, renderError("This request method is not supported."));
            return;
        }

        if (method.equals("POST")) {
            Map<String, String> form = readForm(exchange);
            requireCsrf(session, form.get("csrf"));
            if (path.equals("/setup")) {
                setup(session, form);
                rotateSession(exchange, session);
                redirect(exchange, "/");
                return;
            }
            if (path.equals("/login")) {
                login(exchange, session, form);
                return;
            }
            if (path.equals("/logout")) {
                session.authenticated = false;
                SESSIONS.remove(session.id);
                exchange.getResponseHeaders().add("Set-Cookie",
                        SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                redirect(exchange, "/login");
                return;
            }
            if (!requireAuthenticated(exchange, session)) {
                return;
            }
            if (path.equals("/")) {
                performAction(exchange, session, form);
                return;
            }
        }

        if (!path.equals("/")) {
            sendHtml(exchange, 404, renderError("Page not found."));
            return;
        }
        if (!method.equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
            sendHtml(exchange, 405, renderError("This request method is not supported."));
            return;
        }
        if (store.admin == null) {
            redirect(exchange, "/setup");
            return;
        }
        if (!session.authenticated) {
            redirect(exchange, "/login");
            return;
        }
        String page = query.getOrDefault("page", "dashboard");
        sendHtml(exchange, 200, renderPage(session, page, query, null));
    }

    private boolean requireAuthenticated(HttpExchange exchange, WebSession session)
            throws IOException {
        if (!session.authenticated || store.admin == null) {
            redirect(exchange, store.admin == null ? "/setup" : "/login");
            return false;
        }
        return true;
    }

    private void setup(WebSession session, Map<String, String> form) throws IOException {
        if (store.admin != null) {
            throw new IllegalArgumentException("Administrator setup has already been completed.");
        }
        String username = required(form, "username");
        char[] password = required(form, "password").toCharArray();
        char[] confirmation = required(form, "confirmPassword").toCharArray();
        try {
            if (!MessageDigest.isEqual(
                    new String(password).getBytes(StandardCharsets.UTF_8),
                    new String(confirmation).getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("The passwords do not match.");
            }
            Main.createAdministrator(store, username, password);
            session.authenticated = true;
            session.username = store.admin.username();
        } catch (GeneralSecurityException e) {
            throw new IOException("The secure password service is unavailable.", e);
        } finally {
            java.util.Arrays.fill(password, '\0');
            java.util.Arrays.fill(confirmation, '\0');
        }
    }

    private void login(HttpExchange exchange, WebSession session, Map<String, String> form)
            throws IOException {
        if (store.admin == null) {
            redirect(exchange, "/setup");
            return;
        }
        if (session.loginAttempts >= 5) {
            sendHtml(exchange, 429, renderLogin(session,
                    "Too many failed sign-in attempts. Restart the application to try again."));
            return;
        }
        char[] password = required(form, "password").toCharArray();
        boolean valid;
        try {
            valid = Main.verifyAdministratorPassword(store, required(form, "username"), password);
        } catch (GeneralSecurityException e) {
            throw new IOException("The secure password service is unavailable.", e);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        if (!valid) {
            session.loginAttempts++;
            sendHtml(exchange, 401, renderLogin(session,
                    "Incorrect username or password. "
                            + (5 - session.loginAttempts) + " attempt(s) remaining."));
            return;
        }
        session.authenticated = true;
        session.username = store.admin.username();
        rotateSession(exchange, session);
        redirect(exchange, "/");
    }

    private void performAction(HttpExchange exchange, WebSession session,
                               Map<String, String> form) throws IOException {
        String action = required(form, "action");
        try {
            switch (action) {
                case "employee-save" -> saveEmployee(form);
                case "employee-delete" -> deleteEmployee(form);
                case "attendance-save" -> saveAttendance(form);
                case "leave-save" -> saveLeave(form);
                case "leave-decision" -> decideLeave(form);
                default -> throw new IllegalArgumentException("Unknown action.");
            }
            redirect(exchange, "/?page=" + encodeUrl(form.getOrDefault("returnPage", "dashboard")));
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            String page = form.getOrDefault("returnPage", "dashboard");
            sendHtml(exchange, 400, renderPage(session, page, Map.of(), e.getMessage()));
        }
    }

    private void saveEmployee(Map<String, String> form) throws IOException {
        String id = required(form, "id");
        String name = required(form, "name");
        String designation = required(form, "designation");
        String department = required(form, "department");
        String contact = required(form, "contact");
        String key = idKey(id);
        Employee employee = new Employee(id, name, designation, department, contact);
        String originalId = form.getOrDefault("originalId", "").trim();
        if (!originalId.isEmpty()) {
            Employee existing = store.employees.get(idKey(originalId));
            if (existing == null) {
                throw new IllegalArgumentException("The employee to update no longer exists.");
            }
            if (!idKey(originalId).equals(key) && store.employees.containsKey(key)) {
                throw new IllegalArgumentException("An employee with that ID already exists.");
            }
            store.employees.remove(idKey(originalId));
            store.employees.put(key, employee);
            updateEmployeeReferences(originalId, id);
        } else {
            if (store.employees.containsKey(key)) {
                throw new IllegalArgumentException("An employee with that ID already exists.");
            }
            store.employees.put(key, employee);
        }
        store.save();
    }

    private void updateEmployeeReferences(String oldId, String newId) {
        for (int i = 0; i < store.attendance.size(); i++) {
            Attendance item = store.attendance.get(i);
            if (item.employeeId().equalsIgnoreCase(oldId)) {
                store.attendance.set(i, new Attendance(newId, item.date(), item.status()));
            }
        }
        for (int i = 0; i < store.leaveRequests.size(); i++) {
            LeaveRequest item = store.leaveRequests.get(i);
            if (item.employeeId().equalsIgnoreCase(oldId)) {
                store.leaveRequests.set(i, new LeaveRequest(item.id(), newId, item.type(),
                        item.startDate(), item.endDate(), item.status()));
            }
        }
    }

    private void deleteEmployee(Map<String, String> form) throws IOException {
        String id = required(form, "id");
        if (store.employees.remove(idKey(id)) == null) {
            throw new IllegalArgumentException("Employee not found.");
        }
        store.attendance.removeIf(item -> item.employeeId().equalsIgnoreCase(id));
        store.leaveRequests.removeIf(item -> item.employeeId().equalsIgnoreCase(id));
        store.save();
    }

    private void saveAttendance(Map<String, String> form) throws IOException {
        String employeeId = required(form, "employeeId");
        if (!store.employees.containsKey(idKey(employeeId))) {
            throw new IllegalArgumentException("Employee not found.");
        }
        LocalDate date = parseDate(required(form, "date"), "Attendance date");
        AttendanceStatus status;
        try {
            status = AttendanceStatus.valueOf(required(form, "status"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Choose a valid attendance status.");
        }
        int index = -1;
        for (int i = 0; i < store.attendance.size(); i++) {
            Attendance item = store.attendance.get(i);
            if (item.employeeId().equalsIgnoreCase(employeeId) && item.date().equals(date)) {
                index = i;
                break;
            }
        }
        Attendance record = new Attendance(employeeId, date, status);
        if (index < 0) {
            store.attendance.add(record);
        } else {
            store.attendance.set(index, record);
        }
        store.save();
    }

    private void saveLeave(Map<String, String> form) throws IOException {
        String employeeId = required(form, "employeeId");
        if (!store.employees.containsKey(idKey(employeeId))) {
            throw new IllegalArgumentException("Employee not found.");
        }
        String type = required(form, "type");
        LocalDate start = parseDate(required(form, "startDate"), "Start date");
        LocalDate end = parseDate(required(form, "endDate"), "End date");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("End date cannot be before start date.");
        }
        store.leaveRequests.add(new LeaveRequest(store.nextLeaveId++, employeeId, type,
                start, end, LeaveStatus.PENDING));
        store.save();
    }

    private void decideLeave(Map<String, String> form) throws IOException {
        long id;
        try {
            id = Long.parseLong(required(form, "id"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter a valid leave request ID.");
        }
        LeaveStatus status;
        try {
            status = LeaveStatus.valueOf(required(form, "status"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Choose approve or reject.");
        }
        if (status == LeaveStatus.PENDING) {
            throw new IllegalArgumentException("Choose approve or reject.");
        }
        for (int i = 0; i < store.leaveRequests.size(); i++) {
            LeaveRequest item = store.leaveRequests.get(i);
            if (item.id() == id) {
                if (item.status() != LeaveStatus.PENDING) {
                    throw new IllegalArgumentException("This leave request has already been decided.");
                }
                store.leaveRequests.set(i, new LeaveRequest(item.id(), item.employeeId(),
                        item.type(), item.startDate(), item.endDate(), status));
                store.save();
                return;
            }
        }
        throw new IllegalArgumentException("Leave request not found.");
    }

    private String renderPage(WebSession session, String page, Map<String, String> query,
                              String error) {
        String content = switch (page) {
            case "employees" -> employeesPage(session, query);
            case "attendance" -> attendancePage(session, query);
            case "leaves" -> leavesPage(session);
            case "reports" -> reportsPage(session);
            case "search" -> searchPage(session, query);
            case "dashboard" -> dashboardPage(session);
            default -> "<section class=\"panel\"><h1>Page not found</h1>"
                    + "<a href=\"/\">Return to dashboard</a></section>";
        };
        return shell(session, page, (error == null ? "" : alert(error)) + content);
    }

    private String dashboardPage(WebSession session) {
        LocalDate today = LocalDate.now();
        List<Attendance> todayRecords = store.attendance.stream()
                .filter(item -> item.date().equals(today)).toList();
        long present = countAttendance(todayRecords, AttendanceStatus.PRESENT);
        long absent = countAttendance(todayRecords, AttendanceStatus.ABSENT);
        long onLeave = countAttendance(todayRecords, AttendanceStatus.ON_LEAVE);
        long marked = todayRecords.stream().map(item -> idKey(item.employeeId())).distinct().count();
        long departments = store.employees.values().stream()
                .map(item -> item.department().toLowerCase(Locale.ROOT)).distinct().count();
        long pending = store.leaveRequests.stream()
                .filter(item -> item.status() == LeaveStatus.PENDING).count();
        StringBuilder html = new StringBuilder();
        html.append("<section class=\"welcome\"><div><p class=\"eyebrow\">PEOPLE OPERATIONS</p>")
                .append("<h1>Good day, ").append(escape(session.username)).append("</h1>")
                .append("<p class=\"muted\">Your HR overview for ")
                .append(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu", Locale.ENGLISH)))
                .append(".</p></div><a class=\"button\" href=\"/?page=employees\">Manage employees</a></section>")
                .append("<section class=\"stats\">")
                .append(statCard("Total employees", Integer.toString(store.employees.size()), "People in your directory", "blue"))
                .append(statCard("Departments", Long.toString(departments), "Across the organization", "purple"))
                .append(statCard("Present today", Long.toString(present), "Attendance recorded", "green"))
                .append(statCard("Pending leave", Long.toString(pending), "Requests need your review", "amber"))
                .append("</section>")
                .append("<section class=\"grid-two\"><article class=\"panel\"><div class=\"panel-heading\"><div>")
                .append("<p class=\"eyebrow\">TODAY</p><h2>Attendance snapshot</h2></div>")
                .append("<a href=\"/?page=attendance\">View attendance</a></div><div class=\"attendance-summary\">")
                .append(statusMetric("Present", present, "green")).append(statusMetric("Absent", absent, "red"))
                .append(statusMetric("On leave", onLeave, "amber"))
                .append(statusMetric("Not marked", Math.max(0, store.employees.size() - marked), "blue"))
                .append("</div></article><article class=\"panel\"><div class=\"panel-heading\"><div>")
                .append("<p class=\"eyebrow\">NEEDS ATTENTION</p><h2>Pending leave requests</h2></div>")
                .append("<a href=\"/?page=leaves\">Review all</a></div>");
        List<LeaveRequest> pendingRequests = store.leaveRequests.stream()
                .filter(item -> item.status() == LeaveStatus.PENDING).limit(4).toList();
        if (pendingRequests.isEmpty()) {
            html.append("<p class=\"empty\">You’re all caught up. No requests need review.</p>");
        } else {
            html.append("<div class=\"request-list\">");
            for (LeaveRequest item : pendingRequests) {
                Employee employee = store.employees.get(idKey(item.employeeId()));
                html.append("<div class=\"request-row\"><div class=\"avatar\">")
                        .append(escape(initials(employee == null ? item.employeeId() : employee.name())))
                        .append("</div><div class=\"request-main\"><strong>")
                        .append(escape(employee == null ? item.employeeId() : employee.name()))
                        .append("</strong><span>").append(escape(item.type())).append(" · ")
                        .append(formatDate(item.startDate())).append(" – ")
                        .append(formatDate(item.endDate())).append("</span></div><span class=\"badge pending\">Pending</span></div>");
            }
            html.append("</div>");
        }
        html.append("</article></section><section class=\"panel quick-panel\"><div>")
                .append("<p class=\"eyebrow\">QUICK ACCESS</p><h2>What would you like to do?</h2></div>")
                .append("<div class=\"quick-links\"><a href=\"/?page=employees\">＋ Add an employee</a>")
                .append("<a href=\"/?page=attendance\">◷ Record attendance</a>")
                .append("<a href=\"/?page=leaves\">▤ Manage leave</a>")
                .append("<a href=\"/?page=reports\">▥ View reports</a></div></section>");
        return html.toString();
    }

    private String employeesPage(WebSession session, Map<String, String> query) {
        String editId = query.get("edit");
        Employee editing = editId == null ? null : store.employees.get(idKey(editId));
        StringBuilder html = new StringBuilder("<section class=\"page-heading\"><div><p class=\"eyebrow\">PEOPLE DIRECTORY</p><h1>Employees</h1><p class=\"muted\">Manage employee details in one place.</p></div></section>")
                .append("<section class=\"panel\"><h2>")
                .append(editing == null ? "Add employee" : "Update employee")
                .append("</h2><form method=\"post\" action=\"/\" class=\"form-grid\">")
                .append(hidden("csrf", session.csrf)).append(hidden("action", "employee-save"))
                .append(hidden("returnPage", "employees"));
        if (editing != null) {
            html.append(hidden("originalId", editing.id()));
        }
        html.append(input("Employee ID", "id", editing == null ? "" : editing.id(), editing == null))
                .append(input("Full name", "name", editing == null ? "" : editing.name(), false))
                .append(input("Designation", "designation", editing == null ? "" : editing.designation(), false))
                .append(input("Department", "department", editing == null ? "" : editing.department(), false))
                .append(input("Contact information", "contact", editing == null ? "" : editing.contact(), false))
                .append("<div class=\"form-actions\"><button class=\"button\" type=\"submit\">")
                .append(editing == null ? "Add employee" : "Save changes").append("</button>");
        if (editing != null) {
            html.append("<a class=\"button secondary\" href=\"/?page=employees\">Cancel</a>");
        }
        html.append("</div></form></section><section class=\"panel\"><div class=\"panel-heading\"><div><p class=\"eyebrow\">DIRECTORY</p><h2>All employees</h2></div><span class=\"count-pill\">")
                .append(store.employees.size()).append(" total</span></div>");
        if (store.employees.isEmpty()) {
            html.append("<p class=\"empty\">No employees yet. Add the first employee above.</p>");
        } else {
            html.append("<div class=\"table-wrap\"><table><thead><tr><th>Employee</th><th>ID</th><th>Designation</th><th>Department</th><th>Contact</th><th>Actions</th></tr></thead><tbody>");
            for (Employee employee : store.employees.values()) {
                html.append("<tr><td><strong>").append(escape(employee.name()))
                        .append("</strong></td><td>").append(escape(employee.id()))
                        .append("</td><td>").append(escape(employee.designation()))
                        .append("</td><td>").append(escape(employee.department()))
                        .append("</td><td>").append(escape(employee.contact()))
                        .append("</td><td class=\"actions\"><a href=\"/?page=employees&edit=")
                        .append(encodeUrl(employee.id())).append("\">Edit</a>")
                        .append(postButton(session, "employee-delete", "employees", employee.id(),
                                "", "Delete", "danger")).append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        return html.append("</section>").toString();
    }

    private String attendancePage(WebSession session, Map<String, String> query) {
        LocalDate today = LocalDate.now();
        StringBuilder html = new StringBuilder("<section class=\"page-heading\"><div><p class=\"eyebrow\">DAILY RECORDS</p><h1>Attendance</h1><p class=\"muted\">Record and review attendance by date.</p></div></section>")
                .append("<section class=\"panel\"><h2>Record attendance</h2>");
        if (store.employees.isEmpty()) {
            html.append("<p class=\"empty\">Add an employee before recording attendance.</p>");
        } else {
            html.append("<form method=\"post\" action=\"/\" class=\"form-grid\">")
                    .append(hidden("csrf", session.csrf)).append(hidden("action", "attendance-save"))
                    .append(hidden("returnPage", "attendance")).append("<label>Employee<select name=\"employeeId\" required>");
            for (Employee employee : store.employees.values()) {
                html.append("<option value=\"").append(escape(employee.id())).append("\">")
                        .append(escape(employee.name())).append(" · ").append(escape(employee.id()))
                        .append("</option>");
            }
            html.append("</select></label>").append(input("Date", "date", today.toString(), false, "date"))
                    .append("<label>Status<select name=\"status\" required>");
            for (AttendanceStatus status : AttendanceStatus.values()) {
                html.append("<option value=\"").append(status).append("\">")
                        .append(statusLabel(status)).append("</option>");
            }
            html.append("</select></label><div class=\"form-actions\"><button class=\"button\" type=\"submit\">Save attendance</button></div></form>");
        }
        String dateFilter = query.getOrDefault("date", today.toString());
        html.append("</section><section class=\"panel\"><div class=\"panel-heading\"><div><p class=\"eyebrow\">RECORDS</p><h2>Attendance for a date</h2></div><form method=\"get\" class=\"inline-form\"><input type=\"hidden\" name=\"page\" value=\"attendance\"><input type=\"date\" name=\"date\" value=\"")
                .append(escape(dateFilter)).append("\"><button class=\"button secondary\" type=\"submit\">Filter</button></form></div>");
        LocalDate parsedDate;
        try {
            parsedDate = LocalDate.parse(dateFilter);
        } catch (java.time.DateTimeException e) {
            parsedDate = today;
        }
        LocalDate selectedDate = parsedDate;
        List<Attendance> records = store.attendance.stream()
                .filter(item -> item.date().equals(selectedDate))
                .toList();
        html.append(attendanceTable(records));
        return html.append("</section>").toString();
    }

    private String leavesPage(WebSession session) {
        StringBuilder html = new StringBuilder("<section class=\"page-heading\"><div><p class=\"eyebrow\">TIME OFF</p><h1>Leave management</h1><p class=\"muted\">Review employee leave requests and decisions.</p></div></section>")
                .append("<section class=\"panel\"><h2>Add leave request</h2>");
        if (store.employees.isEmpty()) {
            html.append("<p class=\"empty\">Add an employee before creating a leave request.</p>");
        } else {
            html.append("<form method=\"post\" action=\"/\" class=\"form-grid\">")
                    .append(hidden("csrf", session.csrf)).append(hidden("action", "leave-save"))
                    .append(hidden("returnPage", "leaves")).append("<label>Employee<select name=\"employeeId\" required>");
            for (Employee employee : store.employees.values()) {
                html.append("<option value=\"").append(escape(employee.id())).append("\">")
                        .append(escape(employee.name())).append(" · ").append(escape(employee.id()))
                        .append("</option>");
            }
            html.append("</select></label>").append(input("Leave type", "type", "", false))
                    .append(input("Start date", "startDate", LocalDate.now().toString(), false, "date"))
                    .append(input("End date", "endDate", LocalDate.now().toString(), false, "date"))
                    .append("<div class=\"form-actions\"><button class=\"button\" type=\"submit\">Add request</button></div></form>");
        }
        html.append("</section><section class=\"panel\"><div class=\"panel-heading\"><div><p class=\"eyebrow\">REQUESTS</p><h2>All leave requests</h2></div><span class=\"count-pill\">")
                .append(store.leaveRequests.size()).append(" total</span></div>");
        if (store.leaveRequests.isEmpty()) {
            html.append("<p class=\"empty\">No leave requests have been submitted.</p>");
        } else {
            html.append("<div class=\"table-wrap\"><table><thead><tr><th>Request</th><th>Employee</th><th>Leave type</th><th>Dates</th><th>Status</th><th>Decision</th></tr></thead><tbody>");
            for (LeaveRequest item : store.leaveRequests) {
                Employee employee = store.employees.get(idKey(item.employeeId()));
                html.append("<tr><td>#").append(item.id()).append("</td><td>")
                        .append(escape(employee == null ? item.employeeId() : employee.name()))
                        .append("<span class=\"subcell\">").append(escape(item.employeeId()))
                        .append("</span></td><td>").append(escape(item.type()))
                        .append("</td><td>").append(formatDate(item.startDate())).append(" – ")
                        .append(formatDate(item.endDate())).append("</td><td>")
                        .append(statusBadge(item.status().name())).append("</td><td>");
                if (item.status() == LeaveStatus.PENDING) {
                    html.append(postButton(session, "leave-decision", "leaves",
                                    Long.toString(item.id()), "APPROVED", "Approve", "small"))
                            .append(postDecisionButton(session, item.id(), "REJECTED"));
                } else {
                    html.append("<span class=\"muted\">Reviewed</span>");
                }
                html.append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        return html.append("</section>").toString();
    }

    private String reportsPage(WebSession session) {
        LocalDate today = LocalDate.now();
        long present = countAttendance(store.attendance, AttendanceStatus.PRESENT);
        long absent = countAttendance(store.attendance, AttendanceStatus.ABSENT);
        long onLeave = countAttendance(store.attendance, AttendanceStatus.ON_LEAVE);
        long pending = store.leaveRequests.stream().filter(item -> item.status() == LeaveStatus.PENDING).count();
        long approved = store.leaveRequests.stream().filter(item -> item.status() == LeaveStatus.APPROVED).count();
        long rejected = store.leaveRequests.stream().filter(item -> item.status() == LeaveStatus.REJECTED).count();
        StringBuilder html = new StringBuilder("<section class=\"page-heading\"><div><p class=\"eyebrow\">INSIGHTS</p><h1>Reports</h1><p class=\"muted\">A summary of employee, attendance, and leave activity.</p></div></section>")
                .append("<section class=\"stats stats-three\">")
                .append(statCard("Employee directory", Integer.toString(store.employees.size()), "Current employees", "blue"))
                .append(statCard("Attendance records", Integer.toString(store.attendance.size()), "All recorded dates", "green"))
                .append(statCard("Leave requests", Integer.toString(store.leaveRequests.size()), "All request statuses", "purple"))
                .append("</section><section class=\"grid-two\"><article class=\"panel\"><p class=\"eyebrow\">ATTENDANCE</p><h2>Recorded status totals</h2><div class=\"report-list\">")
                .append(reportRow("Present", present, "green")).append(reportRow("Absent", absent, "red"))
                .append(reportRow("On leave", onLeave, "amber"))
                .append("</div><p class=\"muted\">Today is ").append(formatDate(today)).append(".</p></article>")
                .append("<article class=\"panel\"><p class=\"eyebrow\">LEAVE</p><h2>Request summary</h2><div class=\"report-list\">")
                .append(reportRow("Pending", pending, "amber")).append(reportRow("Approved", approved, "green"))
                .append(reportRow("Rejected", rejected, "red")).append("</div></article></section>")
                .append("<section class=\"panel\"><div class=\"panel-heading\"><div><p class=\"eyebrow\">EMPLOYEES</p><h2>Employee directory</h2></div><a href=\"/?page=employees\">Manage directory</a></div>");
        if (store.employees.isEmpty()) {
            html.append("<p class=\"empty\">No employee data to report.</p>");
        } else {
            html.append("<div class=\"table-wrap\"><table><thead><tr><th>ID</th><th>Name</th><th>Designation</th><th>Department</th></tr></thead><tbody>");
            for (Employee item : store.employees.values()) {
                html.append("<tr><td>").append(escape(item.id())).append("</td><td>")
                        .append(escape(item.name())).append("</td><td>")
                        .append(escape(item.designation())).append("</td><td>")
                        .append(escape(item.department())).append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        return html.append("</section>").toString();
    }

    private String searchPage(WebSession session, Map<String, String> query) {
        String term = query.getOrDefault("q", "").trim();
        String by = query.getOrDefault("by", "name");
        List<Employee> matches = new ArrayList<>();
        if (!term.isBlank()) {
            String needle = term.toLowerCase(Locale.ROOT);
            for (Employee employee : store.employees.values()) {
                String candidate = switch (by) {
                    case "id" -> employee.id();
                    case "department" -> employee.department();
                    default -> employee.name();
                };
                if (candidate.toLowerCase(Locale.ROOT).contains(needle)) {
                    matches.add(employee);
                }
            }
        }
        StringBuilder html = new StringBuilder("<section class=\"page-heading\"><div><p class=\"eyebrow\">FIND A PERSON</p><h1>Employee search</h1><p class=\"muted\">Search by name, employee ID, or department.</p></div></section>")
                .append("<section class=\"panel\"><form method=\"get\" class=\"search-form\"><input type=\"hidden\" name=\"page\" value=\"search\"><label class=\"grow\">Search employees<input name=\"q\" value=\"")
                .append(escape(term)).append("\" placeholder=\"Type a name, ID, or department\"></label><label>Search by<select name=\"by\">")
                .append(option("name", "Name", by)).append(option("id", "Employee ID", by))
                .append(option("department", "Department", by))
                .append("</select></label><button class=\"button\" type=\"submit\">Search</button></form></section>");
        if (!term.isBlank()) {
            html.append("<section class=\"panel\"><div class=\"panel-heading\"><h2>Results</h2><span class=\"count-pill\">")
                    .append(matches.size()).append(matches.size() == 1 ? " match" : " matches")
                    .append("</span></div>");
            if (matches.isEmpty()) {
                html.append("<p class=\"empty\">No employees match “").append(escape(term)).append("”.</p>");
            } else {
                html.append("<div class=\"table-wrap\"><table><thead><tr><th>Employee</th><th>ID</th><th>Designation</th><th>Department</th><th>Contact</th></tr></thead><tbody>");
                for (Employee item : matches) {
                    html.append("<tr><td>").append(escape(item.name())).append("</td><td>")
                            .append(escape(item.id())).append("</td><td>")
                            .append(escape(item.designation())).append("</td><td>")
                            .append(escape(item.department())).append("</td><td>")
                            .append(escape(item.contact())).append("</td></tr>");
                }
                html.append("</tbody></table></div>");
            }
            html.append("</section>");
        }
        return html.toString();
    }

    private String attendanceTable(List<Attendance> records) {
        if (records.isEmpty()) {
            return "<p class=\"empty\">No attendance records for this date.</p>";
        }
        StringBuilder html = new StringBuilder("<div class=\"table-wrap\"><table><thead><tr><th>Employee</th><th>Employee ID</th><th>Date</th><th>Status</th></tr></thead><tbody>");
        for (Attendance item : records) {
            Employee employee = store.employees.get(idKey(item.employeeId()));
            html.append("<tr><td>").append(escape(employee == null ? "Unknown employee" : employee.name()))
                    .append("</td><td>").append(escape(item.employeeId()))
                    .append("</td><td>").append(formatDate(item.date()))
                    .append("</td><td>").append(statusBadge(statusLabel(item.status())))
                    .append("</td></tr>");
        }
        return html.append("</tbody></table></div>").toString();
    }

    private String renderLogin(WebSession session, String error) {
        if (store.admin == null) {
            return renderSetup(session, error);
        }
        String content = "<main class=\"auth-wrap\"><section class=\"auth-card\"><div class=\"brand-mark\">H</div>"
                + "<p class=\"eyebrow\">HRMS · PEOPLE OPERATIONS</p><h1>Welcome back</h1>"
                + "<p class=\"muted\">Sign in with your administrator account.</p>"
                + (error == null ? "" : alert(error))
                + "<form method=\"post\" action=\"/login\" class=\"stack-form\">"
                + hidden("csrf", session.csrf) + input("Administrator username", "username", "", false)
                + input("Password", "password", "", false, "password")
                + "<button class=\"button full\" type=\"submit\">Sign in</button></form>"
                + "<p class=\"security-note\">Your account protects access to employee records.</p></section></main>";
        return shell(null, "", content);
    }

    private String renderSetup(WebSession session, String error) {
        if (store.admin != null) {
            return renderLogin(session, error);
        }
        String content = "<main class=\"auth-wrap\"><section class=\"auth-card\"><div class=\"brand-mark\">H</div>"
                + "<p class=\"eyebrow\">HRMS · FIRST-TIME SETUP</p><h1>Create administrator</h1>"
                + "<p class=\"muted\">Set up the account used to manage this HRMS.</p>"
                + (error == null ? "" : alert(error))
                + "<form method=\"post\" action=\"/setup\" class=\"stack-form\">"
                + hidden("csrf", session.csrf) + input("Administrator username", "username", "", false)
                + input("Password (at least 10 characters)", "password", "", false, "password")
                + input("Confirm password", "confirmPassword", "", false, "password")
                + "<button class=\"button full\" type=\"submit\">Create account</button></form>"
                + "<p class=\"security-note\">Choose a unique password and keep it private.</p></section></main>";
        return shell(null, "", content);
    }

    private String shell(WebSession session, String activePage, String content) {
        String username = session == null ? null : session.username;
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"color-scheme\" content=\"light\"><title>HRMS · People Operations</title><style>")
                .append(CSS).append("</style></head><body>");
        if (username == null) {
            return html.append(content).append("</body></html>").toString();
        }
        html.append("<aside class=\"sidebar\"><a class=\"brand\" href=\"/\"><span class=\"brand-mark\">H</span><span>People<span class=\"brand-light\">Desk</span><small>HUMAN RESOURCES</small></span></a>")
                .append("<p class=\"nav-label\">WORKSPACE</p><nav>")
                .append(navItem("dashboard", "Overview", "⌂", activePage))
                .append(navItem("employees", "Employees", "♙", activePage))
                .append(navItem("attendance", "Attendance", "◷", activePage))
                .append(navItem("leaves", "Leave management", "▤", activePage))
                .append(navItem("reports", "Reports", "▥", activePage))
                .append(navItem("search", "Employee search", "⌕", activePage))
                .append("</nav><div class=\"sidebar-bottom\"><div class=\"admin-avatar\">")
                .append(escape(initials(username))).append("</div><div class=\"admin-info\"><strong>")
                .append(escape(username)).append("</strong><span>Administrator</span></div>")
                .append("<form method=\"post\" action=\"/logout\">")
                .append(hidden("csrf", session.csrf)).append("<button class=\"logout\" type=\"submit\">Sign out</button></form></div></aside>")
                .append("<main class=\"main\"><header class=\"topbar\"><div class=\"breadcrumb\">HRMS <span>/</span> ")
                .append(escape(activePage.isBlank() ? "Account" : title(activePage)))
                .append("</div><div class=\"topbar-right\"><span class=\"status-dot\"></span> Local and secure</div></header>")
                .append("<div class=\"content\">").append(content)
                .append("<footer>PeopleDesk HRMS <span>·</span> Local Java application</footer></div></main>")
                .append("</body></html>");
        return html.toString();
    }

    private String navItem(String page, String label, String icon, String active) {
        return "<a class=\"nav-item " + (page.equals(active) ? "active" : "")
                + "\" href=\"/?page=" + page + "\"><span class=\"nav-icon\">" + icon
                + "</span>" + label + "</a>";
    }

    private String postButton(WebSession session, String action, String returnPage,
                              String id, String status, String label, String style) {
        return "<form class=\"inline-form\" method=\"post\" action=\"/\">"
                + hidden("csrf", session.csrf) + hidden("action", action)
                + hidden("returnPage", returnPage) + hidden("id", id)
                + hidden("status", status)
                + "<button class=\"link-button " + style + "\" type=\"submit\">" + label
                + "</button></form>";
    }

    private String postDecisionButton(WebSession session, long id, String status) {
        return "<form class=\"inline-form\" method=\"post\" action=\"/\">"
                + hidden("csrf", session.csrf) + hidden("action", "leave-decision")
                + hidden("returnPage", "leaves") + hidden("id", Long.toString(id))
                + hidden("status", status)
                + "<button class=\"link-button danger\" type=\"submit\">Reject</button></form>";
    }

    private WebSession session(HttpExchange exchange, boolean create) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader != null) {
            for (String cookie : cookieHeader.split(";")) {
                String[] pair = cookie.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals(SESSION_COOKIE)) {
                    WebSession existing = SESSIONS.get(pair[1]);
                    if (existing != null) {
                        return existing;
                    }
                }
            }
        }
        if (!create) {
            return new WebSession("", randomToken());
        }
        String id = UUID.randomUUID().toString();
        WebSession session = new WebSession(id, randomToken());
        SESSIONS.put(id, session);
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + id
                + "; Path=/; HttpOnly; SameSite=Strict");
        return session;
    }

    private void rotateSession(HttpExchange exchange, WebSession session) {
        SESSIONS.remove(session.id);
        session.id = UUID.randomUUID().toString();
        session.csrf = randomToken();
        SESSIONS.put(session.id, session);
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + session.id
                + "; Path=/; HttpOnly; SameSite=Strict");
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void requireCsrf(WebSession session, String supplied) {
        if (session.id.isEmpty() || supplied == null || !MessageDigest.isEqual(
                session.csrf.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Your session expired. Refresh the page and try again.");
        }
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("The submitted form is too large.");
        }
        return parseForm(new String(body, StandardCharsets.UTF_8));
    }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            values.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String required(Map<String, String> form, String field) {
        String value = form.get(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Please complete the " + field + " field.");
        }
        return value.trim();
    }

    private static LocalDate parseDate(String value, String label) {
        try {
            return LocalDate.parse(value);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException(label + " must be a valid date.");
        }
    }

    private static long countAttendance(List<Attendance> records, AttendanceStatus status) {
        return records.stream().filter(item -> item.status() == status).count();
    }

    private static String idKey(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    private static String formatDate(LocalDate date) {
        return date.format(DISPLAY_DATE);
    }

    private static String statusLabel(AttendanceStatus status) {
        return switch (status) {
            case PRESENT -> "Present";
            case ABSENT -> "Absent";
            case ON_LEAVE -> "On leave";
        };
    }

    private static String statusBadge(String status) {
        String normalized = status.toLowerCase(Locale.ROOT).replace('_', '-');
        return "<span class=\"badge " + escape(normalized) + "\">"
                + escape(status.replace('_', ' ')) + "</span>";
    }

    private static String statCard(String label, String value, String detail, String color) {
        return "<article class=\"stat-card\"><div class=\"stat-top\"><span>"
                + escape(label) + "</span><span class=\"stat-icon " + color + "\">"
                + escape(label.substring(0, 1)) + "</span></div><strong class=\"stat-value\">"
                + escape(value) + "</strong><span class=\"stat-detail\">"
                + escape(detail) + "</span></article>";
    }

    private static String statusMetric(String label, long value, String color) {
        return "<div class=\"status-metric\"><span class=\"metric-dot " + color
                + "\"></span><span>" + escape(label) + "</span><strong>"
                + value + "</strong></div>";
    }

    private static String reportRow(String label, long value, String color) {
        return "<div class=\"report-row\"><span><span class=\"metric-dot " + color
                + "\"></span>" + escape(label) + "</span><strong>" + value + "</strong></div>";
    }

    private static String input(String label, String name, String value, boolean readOnly) {
        return input(label, name, value, readOnly, "text");
    }

    private static String input(String label, String name, String value, boolean readOnly,
                                String type) {
        return "<label>" + escape(label) + "<input type=\"" + escape(type) + "\" name=\""
                + escape(name) + "\" value=\"" + escape(value) + "\""
                + (readOnly ? " readonly" : "") + " required></label>";
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + escape(name) + "\" value=\""
                + escape(value) + "\">";
    }

    private static String option(String value, String label, String selected) {
        return "<option value=\"" + escape(value) + "\""
                + (value.equals(selected) ? " selected" : "") + ">"
                + escape(label) + "</option>";
    }

    private static String alert(String message) {
        return "<div class=\"alert\">" + escape(message) + "</div>";
    }

    private static String renderError(String message) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>HRMS · Error</title><style>"
                + CSS + "</style></head><body><main class=\"auth-wrap\"><section class=\"auth-card\">"
                + "<div class=\"brand-mark\">H</div><p class=\"eyebrow\">HRMS</p><h1>Something went wrong</h1>"
                + alert(message) + "<a class=\"button full\" href=\"/\">Return to HRMS</a>"
                + "</section></main></body></html>";
    }

    private static String title(String page) {
        return switch (page) {
            case "employees" -> "Employees";
            case "attendance" -> "Attendance";
            case "leaves" -> "Leave management";
            case "reports" -> "Reports";
            case "search" -> "Employee search";
            default -> "Overview";
        };
    }

    private static String initials(String name) {
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(1, parts[0].length())).toUpperCase(Locale.ROOT);
        }
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1))
                .toUpperCase(Locale.ROOT);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String encodeUrl(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
    }

    private static void sendHtml(HttpExchange exchange, int status, String html)
            throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; "
                        + "base-uri 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class WebSession {
        private String id;
        private String csrf;
        private boolean authenticated;
        private int loginAttempts;
        private String username = "";

        private WebSession(String id, String csrf) {
            this.id = id;
            this.csrf = csrf;
        }
    }

    private static final String CSS = """
            :root{font-family:Inter,"Segoe UI",Arial,sans-serif;color:#1d2939;background:#f5f7fb;font-synthesis:none;font-optical-sizing:auto}
            *{box-sizing:border-box}body{margin:0;min-height:100vh;background:#f5f7fb}a{color:#3166d5;text-decoration:none}a:hover{text-decoration:underline}
            .sidebar{position:fixed;inset:0 auto 0 0;width:246px;background:#fff;border-right:1px solid #e9edf4;padding:25px 18px;display:flex;flex-direction:column;z-index:2}
            .brand{display:flex;align-items:center;gap:11px;padding:3px 9px 36px;color:#17243b;font-weight:750;font-size:17px}.brand:hover{text-decoration:none}
            .brand-mark{display:grid;place-items:center;width:39px;height:39px;border-radius:12px;background:#3268d5;color:white;font-size:21px;font-weight:800;box-shadow:0 5px 12px #3268d533}
            .brand-light{font-weight:500;color:#5b6b82}.brand small{display:block;font-size:9px;letter-spacing:1.25px;color:#8995a8;margin-top:3px}
            .nav-label,.eyebrow{font-size:10px;font-weight:750;letter-spacing:1.3px;color:#8793a6;margin:0 0 10px}.nav-label{padding:0 11px}
            nav{display:grid;gap:5px}.nav-item{display:flex;align-items:center;gap:12px;padding:11px 12px;border-radius:8px;color:#66758b;font-size:13px;font-weight:600}.nav-item:hover,.nav-item.active{background:#edf3ff;color:#2e62ca;text-decoration:none}.nav-icon{width:19px;font-size:17px;text-align:center}
            .sidebar-bottom{margin-top:auto;display:flex;align-items:center;gap:9px;padding:14px 4px 0;border-top:1px solid #edf0f5}.admin-avatar,.avatar{display:grid;place-items:center;flex:none;width:36px;height:36px;border-radius:50%;background:#e9efff;color:#3667c5;font-weight:750;font-size:12px}.admin-info{display:grid;gap:3px;min-width:0}.admin-info strong{font-size:12px;overflow:hidden;text-overflow:ellipsis}.admin-info span{font-size:10px;color:#8995a8}.sidebar-bottom form{margin-left:auto}.logout{border:0;background:none;color:#77849a;cursor:pointer;font-size:11px;padding:5px}
            .main{margin-left:246px;min-height:100vh}.topbar{height:62px;background:#fff;border-bottom:1px solid #e9edf4;display:flex;align-items:center;justify-content:space-between;padding:0 36px}.breadcrumb{font-size:12px;font-weight:650;color:#536279}.breadcrumb span{color:#b3bdcb;padding:0 8px}.topbar-right{font-size:11px;color:#7e8ba0}.status-dot{display:inline-block;width:7px;height:7px;border-radius:50%;background:#35b47a;margin-right:6px}
            .content{max-width:1240px;margin:0 auto;padding:34px 38px 24px}.welcome,.page-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:24px}.welcome h1,.page-heading h1{font-size:26px;letter-spacing:-.6px;margin:4px 0 6px;color:#17243b}.welcome .eyebrow,.page-heading .eyebrow{color:#6281ba}
            .muted{font-size:12px;color:#8a96a8;margin:0;line-height:1.6}.button{display:inline-flex;align-items:center;justify-content:center;min-height:38px;border:0;border-radius:7px;background:#3268d5;color:#fff;padding:0 15px;font:650 12px inherit;cursor:pointer;text-decoration:none;white-space:nowrap}.button:hover{background:#2859bd;text-decoration:none;color:#fff}.button.secondary{background:#eef2f8;color:#55647a}.button.secondary:hover{background:#e2e8f1}.button.full{width:100%;min-height:42px;margin-top:5px}
            .stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:15px;margin-bottom:18px}.stats-three{grid-template-columns:repeat(3,minmax(0,1fr))}.stat-card,.panel{background:#fff;border:1px solid #e9edf4;border-radius:10px;box-shadow:0 2px 7px #1b315008}.stat-card{padding:17px 18px;min-height:132px}.stat-top{display:flex;justify-content:space-between;align-items:center;color:#718096;font-size:11px;font-weight:600}.stat-icon{width:29px;height:29px;display:grid;place-items:center;border-radius:8px;font-size:14px;font-weight:800}.stat-icon.blue{background:#edf3ff;color:#396bcf}.stat-icon.purple{background:#f3efff;color:#7552c4}.stat-icon.green{background:#eaf8f1;color:#279365}.stat-icon.amber{background:#fff5e7;color:#d28a24}.stat-value{display:block;font-size:27px;letter-spacing:-.6px;margin:13px 0 3px;color:#202e43}.stat-detail{font-size:10px;color:#98a2b1}
            .grid-two{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-bottom:17px}.panel{padding:21px;margin-bottom:17px}.panel h2{font-size:15px;margin:0 0 17px;letter-spacing:-.15px}.panel-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.panel-heading h2{margin:2px 0 0}.panel-heading .eyebrow{margin-bottom:3px}.panel-heading>a{font-size:11px;font-weight:650}
            .attendance-summary{display:grid;grid-template-columns:1fr 1fr;gap:14px}.status-metric,.report-row{display:flex;align-items:center;gap:8px;color:#607086;font-size:11px}.status-metric strong{margin-left:auto;color:#27364a;font-size:14px}.metric-dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:3px}.metric-dot.green{background:#30b47b}.metric-dot.red{background:#e46b70}.metric-dot.amber{background:#e8aa48}.metric-dot.blue{background:#5483e5}
            .request-list{display:grid}.request-row{display:flex;align-items:center;gap:10px;padding:10px 0;border-bottom:1px solid #f0f2f6}.request-row:last-child{border-bottom:0}.avatar{width:32px;height:32px;font-size:10px}.request-main{display:grid;gap:4px;min-width:0;flex:1}.request-main strong{font-size:11px}.request-main span{font-size:10px;color:#8793a4}.badge{display:inline-flex;padding:5px 8px;border-radius:20px;font-size:9px;font-weight:700;background:#eff2f6;color:#68768b;white-space:nowrap}.badge.pending,.badge.on-leave{background:#fff5e5;color:#bd811e}.badge.approved,.badge.present{background:#e9f8f0;color:#248959}.badge.rejected,.badge.absent{background:#fff0f0;color:#cb595e}.empty{font-size:11px;color:#929daf;padding:15px 0;margin:0}
            .quick-panel{display:flex;align-items:center;justify-content:space-between;gap:20px}.quick-panel h2{margin-bottom:0}.quick-links{display:flex;flex-wrap:wrap;gap:8px}.quick-links a{padding:9px 11px;background:#f5f7fb;border-radius:7px;color:#50627c;font-size:10px;font-weight:650}.quick-links a:hover{background:#edf3ff;text-decoration:none;color:#3268d5}
            .form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px 16px}.form-grid label,.search-form label,.stack-form label{display:grid;gap:6px;color:#5c6b80;font-size:10px;font-weight:650}.form-grid input,.form-grid select,.search-form input,.search-form select,.stack-form input,.inline-form input[type=date]{height:37px;width:100%;border:1px solid #e0e6ef;border-radius:6px;padding:0 10px;background:#fff;color:#29384d;font:12px inherit;outline:none}.form-grid input:focus,.form-grid select:focus,.search-form input:focus,.search-form select:focus,.stack-form input:focus{border-color:#7da0e8;box-shadow:0 0 0 3px #3268d514}.form-actions{display:flex;align-items:center;gap:9px;grid-column:1/-1}.table-wrap{overflow-x:auto}table{width:100%;border-collapse:collapse;text-align:left;white-space:nowrap}th{background:#f8f9fc;color:#8591a3;font-size:9px;letter-spacing:.55px;text-transform:uppercase;font-weight:700;padding:11px 12px}td{border-bottom:1px solid #eff1f5;padding:12px;color:#5b6a7f;font-size:11px}td strong{color:#2d3b50}.actions{display:flex;align-items:center;gap:10px}.actions>a,.link-button{font-size:10px;font-weight:650}.inline-form{display:inline-flex;align-items:center;gap:7px}.link-button{border:0;background:none;color:#3268d5;cursor:pointer;padding:2px}.link-button.danger{color:#ce6266}.link-button.small{color:#26875e}.subcell{display:block;color:#9aa4b2;font-size:9px;margin-top:3px}.count-pill{border-radius:20px;background:#f1f4f9;color:#68768b;font-size:10px;font-weight:650;padding:6px 10px}.search-form{display:flex;align-items:end;gap:12px}.search-form .grow{flex:1}.search-form label{min-width:150px}.report-list{display:grid;gap:13px;margin:20px 0}.report-row{justify-content:space-between}.report-row>span{display:flex;align-items:center}.report-row strong{font-size:13px;color:#27364a}
            .auth-wrap{min-height:100vh;display:grid;place-items:center;padding:24px;background:radial-gradient(ellipse at 50% 0%,#eaf1ff 0,transparent 55%),#f5f7fb}.auth-card{width:min(100%,405px);padding:34px;background:#fff;border:1px solid #e9edf4;border-radius:13px;box-shadow:0 14px 40px #23395c12}.auth-card .brand-mark{margin-bottom:25px}.auth-card h1{font-size:24px;letter-spacing:-.5px;margin:5px 0}.auth-card>.eyebrow{color:#6281ba}.stack-form{display:grid;gap:14px;margin-top:23px}.stack-form input{height:41px}.security-note{font-size:10px;text-align:center;color:#96a0af;margin:18px 0 0}.alert{padding:11px 12px;border-radius:7px;background:#fff0ef;color:#b85050;font-size:11px;line-height:1.5;margin:15px 0}.main footer{border-top:1px solid #e9edf4;margin-top:28px;padding-top:16px;color:#99a3b1;font-size:9px}.main footer span{padding:0 5px}
            @media(max-width:1050px){.sidebar{width:205px}.main{margin-left:205px}.content{padding:27px 24px}.stats{grid-template-columns:repeat(2,minmax(0,1fr))}.quick-panel{align-items:flex-start;flex-direction:column}}
            @media(max-width:720px){.sidebar{position:static;width:auto;height:auto;padding:12px 14px;border-right:0;border-bottom:1px solid #e9edf4}.brand{padding:0 3px 12px}.nav-label,.sidebar-bottom{display:none}nav{display:flex;overflow:auto;gap:4px}.nav-item{padding:8px;font-size:10px;white-space:nowrap}.nav-icon{display:none}.main{margin-left:0}.topbar{height:48px;padding:0 16px}.topbar-right{font-size:0}.content{padding:23px 14px}.welcome{align-items:flex-start;gap:12px;flex-direction:column}.welcome h1,.page-heading h1{font-size:23px}.stats,.stats-three{gap:9px}.stat-card{padding:13px;min-height:118px}.grid-two{grid-template-columns:1fr}.panel{padding:16px}.form-grid{grid-template-columns:1fr}.form-actions{grid-column:auto}.search-form{align-items:stretch;flex-direction:column}.search-form label{min-width:0}.quick-links{gap:6px}}
            """;
}
