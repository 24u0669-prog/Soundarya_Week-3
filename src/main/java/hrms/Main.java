package hrms;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

public final class Main {
    private static final Scanner INPUT = new Scanner(System.in);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final String RESET = "\u001B[0m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static Console console;

    private Main() {
    }

    public static void main(String[] args) {
        console = System.console();
        try {
            Path dataPath = Path.of(System.getProperty("hrms.data", "hrms-data.txt"))
                    .toAbsolutePath();
            Store store = Store.load(dataPath);
            if (args.length > 0 && args[0].equals("--console")) {
                if (store.admin == null && !createAdministrator(store)) {
                    return;
                }
                if (authenticate(store)) {
                    runMenu(store);
                }
            } else {
                DesktopDashboard.start(store);
            }
        } catch (IOException e) {
            System.err.println(RED + "Unable to load or save HRMS data: " + e.getMessage() + RESET);
        } catch (GeneralSecurityException e) {
            System.err.println(RED + "The secure password service is unavailable: "
                    + e.getMessage() + RESET);
        }
    }

    private static boolean createAdministrator(Store store)
            throws IOException, GeneralSecurityException {
        System.out.println(CYAN + "\n=== First-time administrator setup ===" + RESET);
        System.out.println("Create the HR administrator account used to access this system.");
        String username = readRequired("Administrator username: ");
        char[] password;
        while (true) {
            password = readPassword("Create password (at least 10 characters): ");
            if (password.length >= 10) {
                break;
            }
            clear(password);
            System.out.println(YELLOW + "Password must contain at least 10 characters." + RESET);
        }

        char[] confirmation = readPassword("Confirm password: ");
        if (!java.util.Arrays.equals(password, confirmation)) {
            clear(password);
            clear(confirmation);
            System.out.println(RED + "Passwords did not match. Run the program again to retry." + RESET);
            return false;
        }
        clear(confirmation);

        try {
            createAdministrator(store, username, password);
        } finally {
            clear(password);
        }
        System.out.println(GREEN + "Administrator account created." + RESET);
        return true;
    }

    static void createAdministrator(Store store, String username, char[] password)
            throws IOException, GeneralSecurityException {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Administrator username cannot be empty.");
        }
        if (password == null || password.length < 10) {
            throw new IllegalArgumentException("Password must contain at least 10 characters.");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derivePassword(password, salt);
        store.admin = new AdminCredential(username.trim(), encode(salt), encode(hash));
        store.save();
    }

    private static boolean authenticate(Store store) throws GeneralSecurityException {
        System.out.println(CYAN + "\n=== HRMS administrator login ===" + RESET);
        for (int attempt = 1; attempt <= MAX_LOGIN_ATTEMPTS; attempt++) {
            String username = readRequired("Username: ");
            char[] password = readPassword("Password: ");
            byte[] salt = decode(store.admin.salt);
            byte[] expectedHash = decode(store.admin.passwordHash);
            byte[] enteredHash = derivePassword(password, salt);
            clear(password);
            boolean valid = store.admin.username.equals(username)
                    && MessageDigest.isEqual(expectedHash, enteredHash);
            if (valid) {
                System.out.println(GREEN + "Login successful. Welcome, " + username + "!" + RESET);
                return true;
            }
            System.out.println(RED + "Invalid username or password. "
                    + (MAX_LOGIN_ATTEMPTS - attempt) + " attempt(s) remaining." + RESET);
        }
        System.out.println(RED + "Too many failed login attempts. Exiting." + RESET);
        return false;
    }

    private static void runMenu(Store store) throws IOException {
        while (true) {
            printDashboard(store);
            switch (readChoice("Select an option: ", 0, 5)) {
                case 1 -> employeeMenu(store);
                case 2 -> attendanceMenu(store);
                case 3 -> leaveMenu(store);
                case 4 -> reportMenu(store);
                case 5 -> searchEmployees(store);
                case 0 -> {
                    store.save();
                    System.out.println(GREEN + "Data saved. Goodbye." + RESET);
                    return;
                }
                default -> throw new IllegalStateException("Unexpected menu selection");
            }
        }
    }

    private static void printDashboard(Store store) {
        LocalDate today = LocalDate.now();
        List<Attendance> todaysAttendance = store.attendance.stream()
                .filter(record -> record.date.equals(today))
                .toList();
        long present = countAttendance(todaysAttendance, AttendanceStatus.PRESENT);
        long absent = countAttendance(todaysAttendance, AttendanceStatus.ABSENT);
        long onLeave = countAttendance(todaysAttendance, AttendanceStatus.ON_LEAVE);
        long pending = store.leaveRequests.stream()
                .filter(request -> request.status == LeaveStatus.PENDING).count();
        long departments = store.employees.values().stream()
                .map(employee -> employee.department.toLowerCase(Locale.ROOT))
                .distinct()
                .count();
        long marked = todaysAttendance.stream()
                .map(record -> idKey(record.employeeId))
                .distinct()
                .count();
        long notMarked = Math.max(0, store.employees.size() - marked);

        System.out.println();
        System.out.println(CYAN + "+" + "-".repeat(77) + "+");
        dashboardLine("H R M S   |   PEOPLE OPERATIONS DASHBOARD");
        dashboardLine("Welcome, " + store.admin.username + "    |    "
                + today.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM uuuu", Locale.ENGLISH)));
        System.out.println("+" + "-".repeat(77) + "+");
        printDashboardCards(
                new String[]{"EMPLOYEES", "DEPARTMENTS", "PRESENT TODAY", "PENDING LEAVE"},
                new String[]{Integer.toString(store.employees.size()), Long.toString(departments),
                        Long.toString(present), Long.toString(pending)});
        System.out.println("|" + " ".repeat(77) + "|");
        dashboardLine("TODAY'S ATTENDANCE");
        dashboardLine("Present: " + present + "    |    Absent: " + absent
                + "    |    On leave: " + onLeave + "    |    Not recorded: " + notMarked);
        System.out.println("|" + " ".repeat(77) + "|");
        dashboardLine("PENDING LEAVE REQUESTS");
        List<LeaveRequest> pendingRequests = store.leaveRequests.stream()
                .filter(request -> request.status == LeaveStatus.PENDING)
                .limit(3)
                .toList();
        if (pendingRequests.isEmpty()) {
            dashboardLine("You're all caught up - no requests need review.");
        } else {
            for (LeaveRequest request : pendingRequests) {
                Employee employee = findEmployee(store, request.employeeId);
                String name = employee == null ? request.employeeId : employee.name;
                dashboardLine("#" + request.id + "  " + name + "  |  " + request.type
                        + "  |  " + request.startDate + " to " + request.endDate);
            }
            if (pending > pendingRequests.size()) {
                dashboardLine("...and " + (pending - pendingRequests.size())
                        + " more. Choose Leave Management to review all.");
            }
        }
        dashboardLine("1 Employees     2 Attendance     3 Leave     4 Reports     5 Search     0 Exit");
        System.out.println("+" + "-".repeat(77) + "+" + RESET);
    }

    private static void printDashboardCards(String[] labels, String[] values) {
        printDashboardCardRow(labels);
        printDashboardCardRow(values);
    }

    private static void printDashboardCardRow(String[] values) {
        StringBuilder cards = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                cards.append(' ');
            }
            cards.append(String.format("| %-14s |", values[i]));
        }
        System.out.println("| " + cards + " |");
    }

    private static void dashboardLine(String text) {
        String visibleText = text.length() > 75 ? text.substring(0, 72) + "..." : text;
        System.out.printf("| %-75s |%n", visibleText);
    }

    private static long countAttendance(List<Attendance> records, AttendanceStatus status) {
        return records.stream().filter(record -> record.status == status).count();
    }

    private static void employeeMenu(Store store) throws IOException {
        while (true) {
            System.out.println("""

                    --- Employee management ---
                    1. Add employee
                    2. View all employees
                    3. View employee by ID
                    4. Update employee
                    5. Delete employee
                    0. Back
                    """);
            switch (readChoice("Select an option: ", 0, 5)) {
                case 1 -> addEmployee(store);
                case 2 -> printEmployees(store.employees.values());
                case 3 -> {
                    Employee employee = findEmployee(store, readRequired("Employee ID: "));
                    if (employee == null) {
                        notFound("Employee");
                    } else {
                        printEmployee(employee);
                    }
                }
                case 4 -> updateEmployee(store);
                case 5 -> deleteEmployee(store);
                case 0 -> {
                    return;
                }
                default -> throw new IllegalStateException("Unexpected menu selection");
            }
        }
    }

    private static void addEmployee(Store store) throws IOException {
        String id = readRequired("Employee ID: ");
        if (findEmployee(store, id) != null) {
            System.out.println(RED + "An employee with that ID already exists." + RESET);
            return;
        }
        Employee employee = new Employee(id, readRequired("Name: "),
                readRequired("Designation: "), readRequired("Department: "),
                readRequired("Contact information: "));
        store.employees.put(idKey(id), employee);
        store.save();
        System.out.println(GREEN + "Employee added successfully." + RESET);
    }

    private static void updateEmployee(Store store) throws IOException {
        String id = readRequired("Employee ID to update: ");
        Employee existing = findEmployee(store, id);
        if (existing == null) {
            notFound("Employee");
            return;
        }
        System.out.println("Press Enter to keep the current value.");
        Employee updated = new Employee(existing.id,
                readOptional("Name", existing.name),
                readOptional("Designation", existing.designation),
                readOptional("Department", existing.department),
                readOptional("Contact information", existing.contact));
        store.employees.put(idKey(id), updated);
        store.save();
        System.out.println(GREEN + "Employee updated successfully." + RESET);
    }

    private static void deleteEmployee(Store store) throws IOException {
        String id = readRequired("Employee ID to delete: ");
        Employee employee = findEmployee(store, id);
        if (employee == null) {
            notFound("Employee");
            return;
        }
        if (!readRequired("Delete " + employee.name + " and their attendance and leave records? (y/n): ")
                .equalsIgnoreCase("y")) {
            System.out.println("Deletion cancelled.");
            return;
        }
        store.employees.remove(idKey(id));
        store.attendance.removeIf(record -> record.employeeId.equalsIgnoreCase(id));
        store.leaveRequests.removeIf(request -> request.employeeId.equalsIgnoreCase(id));
        store.save();
        System.out.println(GREEN + "Employee and related records deleted successfully." + RESET);
    }

    private static void attendanceMenu(Store store) throws IOException {
        while (true) {
            System.out.println("""

                    --- Attendance tracking ---
                    1. Record or update attendance
                    2. View attendance by date
                    3. View attendance by employee
                    0. Back
                    """);
            switch (readChoice("Select an option: ", 0, 3)) {
                case 1 -> recordAttendance(store);
                case 2 -> {
                    LocalDate date = readDate("Date (YYYY-MM-DD): ");
                    List<Attendance> records = store.attendance.stream()
                            .filter(record -> record.date.equals(date)).toList();
                    printAttendance(records);
                }
                case 3 -> {
                    String id = readRequired("Employee ID: ");
                    if (findEmployee(store, id) == null) {
                        notFound("Employee");
                        break;
                    }
                    printAttendance(store.attendance.stream()
                            .filter(record -> record.employeeId.equalsIgnoreCase(id))
                            .toList());
                }
                case 0 -> {
                    return;
                }
                default -> throw new IllegalStateException("Unexpected menu selection");
            }
        }
    }

    private static void recordAttendance(Store store) throws IOException {
        String id = readRequired("Employee ID: ");
        if (findEmployee(store, id) == null) {
            notFound("Employee");
            return;
        }
        LocalDate date = readDate("Date (YYYY-MM-DD): ");
        System.out.println("1. Present  2. Absent  3. On leave");
        AttendanceStatus status = switch (readChoice("Status: ", 1, 3)) {
            case 1 -> AttendanceStatus.PRESENT;
            case 2 -> AttendanceStatus.ABSENT;
            case 3 -> AttendanceStatus.ON_LEAVE;
            default -> throw new IllegalStateException("Unexpected attendance selection");
        };
        int existingIndex = -1;
        for (int i = 0; i < store.attendance.size(); i++) {
            Attendance record = store.attendance.get(i);
            if (record.employeeId.equalsIgnoreCase(id) && record.date.equals(date)) {
                existingIndex = i;
                break;
            }
        }
        Attendance record = new Attendance(id, date, status);
        boolean updated = existingIndex >= 0;
        if (updated) {
            store.attendance.set(existingIndex, record);
        } else {
            store.attendance.add(record);
        }
        store.save();
        System.out.println(GREEN + "Attendance " + (updated ? "updated" : "recorded")
                + " successfully: " + status + "." + RESET);
    }

    private static void leaveMenu(Store store) throws IOException {
        while (true) {
            System.out.println("""

                    --- Leave management ---
                    1. Add leave request
                    2. View leave requests
                    3. Approve or reject a request
                    0. Back
                    """);
            switch (readChoice("Select an option: ", 0, 3)) {
                case 1 -> addLeaveRequest(store);
                case 2 -> viewLeaveRequests(store);
                case 3 -> decideLeaveRequest(store);
                case 0 -> {
                    return;
                }
                default -> throw new IllegalStateException("Unexpected menu selection");
            }
        }
    }

    private static void addLeaveRequest(Store store) throws IOException {
        String id = readRequired("Employee ID: ");
        if (findEmployee(store, id) == null) {
            notFound("Employee");
            return;
        }
        String type = readRequired("Leave type: ");
        LocalDate start = readDate("Start date (YYYY-MM-DD): ");
        LocalDate end = readDate("End date (YYYY-MM-DD): ");
        if (end.isBefore(start)) {
            System.out.println(RED + "End date cannot be before start date." + RESET);
            return;
        }
        LeaveRequest request = new LeaveRequest(store.nextLeaveId++, id, type, start, end,
                LeaveStatus.PENDING);
        store.leaveRequests.add(request);
        store.save();
        System.out.println(GREEN + "Leave request #" + request.id + " added with PENDING status."
                + RESET);
    }

    private static void viewLeaveRequests(Store store) {
        System.out.println("1. All requests  2. Filter by employee ID");
        int choice = readChoice("Select an option: ", 1, 2);
        String employeeId = choice == 2 ? readRequired("Employee ID: ") : null;
        if (employeeId != null && findEmployee(store, employeeId) == null) {
            notFound("Employee");
            return;
        }
        List<LeaveRequest> requests = store.leaveRequests.stream()
                .filter(request -> employeeId == null
                        || request.employeeId.equalsIgnoreCase(employeeId))
                .toList();
        printLeaveRequests(requests, store);
    }

    private static void decideLeaveRequest(Store store) throws IOException {
        long id = readLong("Leave request ID: ");
        LeaveRequest request = store.leaveRequests.stream()
                .filter(item -> item.id == id).findFirst().orElse(null);
        if (request == null) {
            notFound("Leave request");
            return;
        }
        if (request.status != LeaveStatus.PENDING) {
            System.out.println(YELLOW + "This request is already " + request.status + "." + RESET);
            return;
        }
        System.out.println("1. Approve  2. Reject");
        LeaveStatus status = readChoice("Decision: ", 1, 2) == 1
                ? LeaveStatus.APPROVED : LeaveStatus.REJECTED;
        int index = store.leaveRequests.indexOf(request);
        store.leaveRequests.set(index, new LeaveRequest(request.id, request.employeeId,
                request.type, request.startDate, request.endDate, status));
        store.save();
        System.out.println(GREEN + "Leave request #" + id + " " + status + "." + RESET);
    }

    private static void searchEmployees(Store store) {
        System.out.println("""

                --- Employee search ---
                1. Search by name
                2. Search by employee ID
                3. Search by department
                0. Back
                """);
        int choice = readChoice("Select an option: ", 0, 3);
        if (choice == 0) {
            return;
        }
        String query = readRequired("Search term: ").toLowerCase(Locale.ROOT);
        List<Employee> matches = store.employees.values().stream()
                .filter(employee -> switch (choice) {
                    case 1 -> employee.name.toLowerCase(Locale.ROOT).contains(query);
                    case 2 -> employee.id.toLowerCase(Locale.ROOT).contains(query);
                    case 3 -> employee.department.toLowerCase(Locale.ROOT).contains(query);
                    default -> false;
                })
                .toList();
        printEmployees(matches);
    }

    private static void reportMenu(Store store) {
        while (true) {
            System.out.println("""

                    --- Reports ---
                    1. Employee directory
                    2. Attendance summary by date range
                    3. Leave summary
                    0. Back
                    """);
            switch (readChoice("Select an option: ", 0, 3)) {
                case 1 -> printEmployees(store.employees.values());
                case 2 -> attendanceReport(store);
                case 3 -> leaveReport(store);
                case 0 -> {
                    return;
                }
                default -> throw new IllegalStateException("Unexpected report selection");
            }
        }
    }

    private static void attendanceReport(Store store) {
        LocalDate start = readDate("Start date (YYYY-MM-DD): ");
        LocalDate end = readDate("End date (YYYY-MM-DD): ");
        if (end.isBefore(start)) {
            System.out.println(RED + "End date cannot be before start date." + RESET);
            return;
        }
        List<Attendance> records = store.attendance.stream()
                .filter(record -> !record.date.isBefore(start) && !record.date.isAfter(end))
                .toList();
        printAttendance(records);
        long present = records.stream().filter(r -> r.status == AttendanceStatus.PRESENT).count();
        long absent = records.stream().filter(r -> r.status == AttendanceStatus.ABSENT).count();
        long onLeave = records.stream().filter(r -> r.status == AttendanceStatus.ON_LEAVE).count();
        System.out.printf("Totals: %d present, %d absent, %d on leave (%d records).%n",
                present, absent, onLeave, records.size());
    }

    private static void leaveReport(Store store) {
        printLeaveRequests(store.leaveRequests, store);
        long pending = store.leaveRequests.stream()
                .filter(request -> request.status == LeaveStatus.PENDING).count();
        long approved = store.leaveRequests.stream()
                .filter(request -> request.status == LeaveStatus.APPROVED).count();
        long rejected = store.leaveRequests.stream()
                .filter(request -> request.status == LeaveStatus.REJECTED).count();
        System.out.printf("Totals: %d pending, %d approved, %d rejected (%d requests).%n",
                pending, approved, rejected, store.leaveRequests.size());
    }

    private static void printEmployees(Iterable<Employee> employees) {
        boolean found = false;
        for (Employee employee : employees) {
            printEmployee(employee);
            found = true;
        }
        if (!found) {
            System.out.println(YELLOW + "No employees found." + RESET);
        }
    }

    private static void printEmployee(Employee employee) {
        System.out.printf("ID: %s | Name: %s | Designation: %s | Department: %s | Contact: %s%n",
                employee.id, employee.name, employee.designation, employee.department,
                employee.contact);
    }

    private static void printAttendance(List<Attendance> records) {
        if (records.isEmpty()) {
            System.out.println(YELLOW + "No attendance records found." + RESET);
            return;
        }
        records.stream().sorted((a, b) -> a.date.compareTo(b.date))
                .forEach(record -> System.out.printf("Date: %s | Employee: %s | Status: %s%n",
                        record.date, record.employeeId, record.status));
    }

    private static void printLeaveRequests(List<LeaveRequest> requests, Store store) {
        if (requests.isEmpty()) {
            System.out.println(YELLOW + "No leave requests found." + RESET);
            return;
        }
        for (LeaveRequest request : requests) {
            Employee employee = findEmployee(store, request.employeeId);
            String name = employee == null ? "(employee unavailable)" : employee.name;
            System.out.printf("#%d | Employee: %s (%s) | Type: %s | %s to %s | Status: %s%n",
                    request.id, name, request.employeeId, request.type, request.startDate,
                    request.endDate, request.status);
        }
    }

    private static Employee findEmployee(Store store, String id) {
        return store.employees.get(idKey(id));
    }

    private static String idKey(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    private static int readChoice(String prompt, int minimum, int maximum) {
        while (true) {
            String value = readRequired(prompt);
            try {
                int choice = Integer.parseInt(value);
                if (choice >= minimum && choice <= maximum) {
                    return choice;
                }
            } catch (NumberFormatException ignored) {
                // The prompt below guides the user to valid input.
            }
            System.out.println(YELLOW + "Enter a number from " + minimum + " to " + maximum + "." + RESET);
        }
    }

    private static long readLong(String prompt) {
        while (true) {
            try {
                return Long.parseLong(readRequired(prompt));
            } catch (NumberFormatException ignored) {
                System.out.println(YELLOW + "Enter a valid whole number." + RESET);
            }
        }
    }

    private static LocalDate readDate(String prompt) {
        while (true) {
            try {
                return LocalDate.parse(readRequired(prompt));
            } catch (DateTimeParseException ignored) {
                System.out.println(YELLOW + "Enter a valid date in YYYY-MM-DD format." + RESET);
            }
        }
    }

    private static String readRequired(String prompt) {
        while (true) {
            System.out.print(prompt);
            String value = INPUT.nextLine().trim();
            if (!value.isEmpty()) {
                return value;
            }
            System.out.println(YELLOW + "This field cannot be empty." + RESET);
        }
    }

    private static String readOptional(String label, String currentValue) {
        System.out.print(label + " [" + currentValue + "]: ");
        String value = INPUT.nextLine().trim();
        return value.isEmpty() ? currentValue : value;
    }

    private static char[] readPassword(String prompt) {
        if (console != null) {
            char[] password = console.readPassword("%s", prompt);
            return password == null ? new char[0] : password;
        }
        System.out.print(prompt);
        System.out.println(YELLOW + "(Warning: password input is visible because no console is attached.)"
                + RESET);
        return INPUT.nextLine().toCharArray();
    }

    static boolean verifyAdministratorPassword(Store store, String username, char[] password)
            throws GeneralSecurityException {
        byte[] salt = decode(store.admin.salt);
        byte[] expectedHash = decode(store.admin.passwordHash);
        byte[] enteredHash = derivePassword(password, salt);
        return store.admin.username.equals(username)
                && MessageDigest.isEqual(expectedHash, enteredHash);
    }

    private static byte[] derivePassword(char[] password, byte[] salt)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PASSWORD_ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static void clear(char[] value) {
        java.util.Arrays.fill(value, '\0');
    }

    private static void notFound(String item) {
        System.out.println(YELLOW + item + " not found." + RESET);
    }

    public enum AttendanceStatus {
        PRESENT, ABSENT, ON_LEAVE
    }

    public enum LeaveStatus {
        PENDING, APPROVED, REJECTED
    }

    record Employee(String id, String name, String designation, String department,
                    String contact) {
    }

    record Attendance(String employeeId, LocalDate date, AttendanceStatus status) {
    }

    record LeaveRequest(long id, String employeeId, String type, LocalDate startDate,
                        LocalDate endDate, LeaveStatus status) {
    }

    record AdminCredential(String username, String salt, String passwordHash) {
    }

    static final class Store {
        private final Path path;
        final Map<String, Employee> employees = new LinkedHashMap<>();
        final List<Attendance> attendance = new ArrayList<>();
        final List<LeaveRequest> leaveRequests = new ArrayList<>();
        AdminCredential admin;
        long nextLeaveId = 1;

        private Store(Path path) {
            this.path = path;
        }

        static Store load(Path path) throws IOException {
            Store store = new Store(path);
            if (!Files.exists(path)) {
                return store;
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
                String line = lines.get(lineNumber);
                if (line.isBlank()) {
                    continue;
                }
                try {
                    String[] fields = line.split("\\|", -1);
                    switch (fields[0]) {
                        case "ADMIN" -> {
                            requireFieldCount(fields, 4);
                            if (store.admin != null) {
                                throw new IllegalArgumentException("Duplicate administrator record");
                            }
                            store.admin = new AdminCredential(decodeText(fields[1]), fields[2], fields[3]);
                            decode(fields[2]);
                            decode(fields[3]);
                        }
                        case "EMPLOYEE" -> {
                            requireFieldCount(fields, 6);
                            Employee employee = new Employee(decodeText(fields[1]),
                                    decodeText(fields[2]), decodeText(fields[3]),
                                    decodeText(fields[4]), decodeText(fields[5]));
                            if (store.employees.putIfAbsent(idKey(employee.id), employee) != null) {
                                throw new IllegalArgumentException("Duplicate employee ID");
                            }
                        }
                        case "ATTENDANCE" -> {
                            requireFieldCount(fields, 4);
                            store.attendance.add(new Attendance(decodeText(fields[1]),
                                    LocalDate.parse(fields[2]), AttendanceStatus.valueOf(fields[3])));
                        }
                        case "LEAVE" -> {
                            requireFieldCount(fields, 7);
                            long requestId = Long.parseLong(fields[1]);
                            store.leaveRequests.add(new LeaveRequest(requestId,
                                    decodeText(fields[2]), decodeText(fields[3]),
                                    LocalDate.parse(fields[4]), LocalDate.parse(fields[5]),
                                    LeaveStatus.valueOf(fields[6])));
                            store.nextLeaveId = Math.max(store.nextLeaveId, requestId + 1);
                        }
                        default -> throw new IllegalArgumentException("Unknown record type");
                    }
                } catch (IllegalArgumentException | DateTimeParseException e) {
                    throw new IOException("Invalid data at line " + (lineNumber + 1) + ": "
                            + e.getMessage(), e);
                }
            }
            return store;
        }

        void save() throws IOException {
            List<String> lines = new ArrayList<>();
            if (admin != null) {
                lines.add(String.join("|", "ADMIN", encodeText(admin.username), admin.salt,
                        admin.passwordHash));
            }
            for (Employee employee : employees.values()) {
                lines.add(String.join("|", "EMPLOYEE", encodeText(employee.id),
                        encodeText(employee.name), encodeText(employee.designation),
                        encodeText(employee.department), encodeText(employee.contact)));
            }
            for (Attendance record : attendance) {
                lines.add(String.join("|", "ATTENDANCE", encodeText(record.employeeId),
                        record.date.toString(), record.status.name()));
            }
            for (LeaveRequest request : leaveRequests) {
                lines.add(String.join("|", "LEAVE", Long.toString(request.id),
                        encodeText(request.employeeId), encodeText(request.type),
                        request.startDate.toString(), request.endDate.toString(),
                        request.status.name()));
            }

            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporaryFile = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(temporaryFile, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static String encodeText(String value) {
            return encode(value.getBytes(StandardCharsets.UTF_8));
        }

        private static String decodeText(String value) {
            return new String(decode(value), StandardCharsets.UTF_8);
        }

        private static void requireFieldCount(String[] fields, int expected) {
            if (fields.length != expected) {
                throw new IllegalArgumentException("Expected " + expected + " fields");
            }
        }

    }
}
