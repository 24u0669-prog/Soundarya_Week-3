package hrms;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;

final class DesktopDashboard {
    private static final Color NAVY = new Color(24, 43, 74);
    private static final Color BLUE = new Color(45, 105, 180);
    private static final Color MUTED = new Color(102, 116, 137);

    private final Main.Store store;
    private final JFrame frame = new JFrame("HRMS | People Operations");
    private final JLabel status = new JLabel("Ready");
    private final JLabel employeesValue = new JLabel();
    private final JLabel departmentsValue = new JLabel();
    private final JLabel presentValue = new JLabel();
    private final JLabel pendingValue = new JLabel();
    private final DefaultTableModel employees = model(
            "Employee ID", "Name", "Designation", "Department", "Contact");
    private final DefaultTableModel attendance = model("Employee ID", "Name", "Date", "Status");
    private final DefaultTableModel leaves = model(
            "Request ID", "Employee ID", "Name", "Type", "Start date", "End date", "Status");
    private final DefaultTableModel pending = model("Request", "Employee", "Type", "Dates");
    private final DefaultTableModel report = model("Report");
    private final JTable employeeTable = new JTable(employees);
    private final JTable attendanceTable = new JTable(attendance);
    private final JTable leaveTable = new JTable(leaves);
    private final JTextField employeeSearch = new JTextField(20);
    private final JTextField attendanceSearch = new JTextField(20);
    private final JComboBox<String> reportType = new JComboBox<>(
            new String[]{"Employee directory", "Attendance summary", "Leave summary"});
    private final JTextField reportStart = new JTextField(
            LocalDate.now().withDayOfMonth(1).toString(), 10);
    private final JTextField reportEnd = new JTextField(LocalDate.now().toString(), 10);

    private DesktopDashboard(Main.Store store) {
        this.store = store;
    }

    static void start(Main.Store store) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                if (store.admin == null && !createAdministrator(store)) {
                    return;
                }
                String username = authenticate(store);
                if (username != null) {
                    new DesktopDashboard(store).show(username);
                }
            } catch (IOException | GeneralSecurityException e) {
                showError(null, "Unable to start HRMS: " + e.getMessage());
            } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
                showError(null, "Unable to initialize the desktop interface: " + e.getMessage());
            }
        });
    }

    private static boolean createAdministrator(Main.Store store)
            throws IOException, GeneralSecurityException {
        JTextField username = new JTextField(20);
        JPasswordField password = new JPasswordField(20);
        JPasswordField confirmation = new JPasswordField(20);
        JPanel fields = form(new String[]{"Administrator username",
                        "Password (10+ characters)", "Confirm password"},
                new Component[]{username, password, confirmation});
        while (JOptionPane.showConfirmDialog(null, fields, "First-time administrator setup",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            char[] first = password.getPassword();
            char[] second = confirmation.getPassword();
            try {
                if (username.getText().isBlank()) {
                    showError(null, "Administrator username cannot be empty.");
                } else if (first.length < 10) {
                    showError(null, "Choose a password with at least 10 characters.");
                } else if (!Arrays.equals(first, second)) {
                    showError(null, "The passwords do not match.");
                } else {
                    Main.createAdministrator(store, username.getText(), first);
                    return true;
                }
            } finally {
                Arrays.fill(first, '\0');
                Arrays.fill(second, '\0');
                password.setText("");
                confirmation.setText("");
            }
        }
        return false;
    }

    private static String authenticate(Main.Store store) throws GeneralSecurityException {
        for (int attempt = 1; attempt <= 5; attempt++) {
            JTextField username = new JTextField(store.admin.username(), 20);
            JPasswordField password = new JPasswordField(20);
            JPanel fields = form(new String[]{"Username", "Password"},
                    new Component[]{username, password});
            if (JOptionPane.showConfirmDialog(null, fields, "HRMS administrator sign in",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
                return null;
            }
            char[] entered = password.getPassword();
            boolean valid;
            try {
                valid = Main.verifyAdministratorPassword(store, username.getText(), entered);
            } finally {
                Arrays.fill(entered, '\0');
                password.setText("");
            }
            if (valid) {
                return store.admin.username();
            }
            showError(null, "Incorrect username or password. "
                    + (5 - attempt) + " attempt(s) remaining.");
        }
        showError(null, "Too many failed sign-in attempts.");
        return null;
    }

    private void show(String username) {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 600));
        frame.setSize(1100, 720);
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());
        frame.add(header(username), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Overview", overviewPanel());
        tabs.addTab("Employees", employeesPanel());
        tabs.addTab("Attendance", attendancePanel());
        tabs.addTab("Leave", leavesPanel());
        tabs.addTab("Reports", reportsPanel());
        frame.add(tabs, BorderLayout.CENTER);
        status.setForeground(MUTED);
        status.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
        frame.add(status, BorderLayout.SOUTH);
        refreshAll();
        frame.setVisible(true);
    }

    private JPanel header(String username) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(NAVY);
        header.setBorder(BorderFactory.createEmptyBorder(16, 22, 16, 22));
        JLabel title = new JLabel("HRMS  |  People Operations");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 21f));
        JLabel user = new JLabel("Administrator: " + username);
        user.setForeground(new Color(220, 230, 243));
        header.add(title, BorderLayout.WEST);
        header.add(user, BorderLayout.EAST);
        return header;
    }

    private JPanel overviewPanel() {
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        JPanel cards = new JPanel(new GridLayout(1, 4, 12, 12));
        cards.add(card("EMPLOYEES", employeesValue));
        cards.add(card("DEPARTMENTS", departmentsValue));
        cards.add(card("PRESENT TODAY", presentValue));
        cards.add(card("PENDING LEAVE", pendingValue));
        JPanel pendingPanel = new JPanel(new BorderLayout(4, 8));
        pendingPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(225, 231, 240)),
                BorderFactory.createEmptyBorder(14, 14, 14, 14)));
        pendingPanel.add(new JLabel("Leave requests needing review"), BorderLayout.NORTH);
        pendingPanel.add(new JScrollPane(new JTable(pending)), BorderLayout.CENTER);
        panel.add(cards, BorderLayout.NORTH);
        panel.add(pendingPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel employeesPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tools.add(new JLabel("Search"));
        tools.add(employeeSearch);
        tools.add(button("Add employee", this::addEmployee));
        tools.add(button("Edit selected", this::editEmployee));
        tools.add(button("Delete selected", this::deleteEmployee));
        employeeSearch.getDocument().addDocumentListener(documentListener(this::refreshEmployees));
        configure(employeeTable);
        panel.add(tools, BorderLayout.NORTH);
        panel.add(new JScrollPane(employeeTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel attendancePanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tools.add(new JLabel("Filter"));
        tools.add(attendanceSearch);
        tools.add(button("Record attendance", this::recordAttendance));
        attendanceSearch.getDocument().addDocumentListener(
                documentListener(this::refreshAttendance));
        configure(attendanceTable);
        panel.add(tools, BorderLayout.NORTH);
        panel.add(new JScrollPane(attendanceTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel leavesPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tools.add(button("New leave request", this::addLeave));
        tools.add(button("Approve / reject selected", this::decideLeave));
        configure(leaveTable);
        panel.add(tools, BorderLayout.NORTH);
        panel.add(new JScrollPane(leaveTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel reportsPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tools.add(reportType);
        tools.add(new JLabel("From"));
        tools.add(reportStart);
        tools.add(new JLabel("To"));
        tools.add(reportEnd);
        tools.add(button("Generate report", this::generateReport));
        configure(new JTable(report));
        panel.add(tools, BorderLayout.NORTH);
        panel.add(new JScrollPane(new JTable(report)), BorderLayout.CENTER);
        return panel;
    }

    private JPanel card(String title, JLabel value) {
        JPanel card = new JPanel(new BorderLayout(4, 10));
        card.setBackground(new Color(245, 248, 252));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(222, 230, 240)),
                BorderFactory.createEmptyBorder(14, 14, 14, 14)));
        JLabel label = new JLabel(title);
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        value.setForeground(BLUE);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 25f));
        card.add(label, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        return card;
    }

    private void addEmployee() {
        String[] values = prompt("Add employee", new String[]{
                "Employee ID", "Name", "Designation", "Department", "Contact"});
        if (values == null) return;
        String key = idKey(values[0]);
        if (store.employees.containsKey(key)) {
            showError(frame, "An employee with that ID already exists.");
            return;
        }
        store.employees.put(key, new Main.Employee(values[0], values[1], values[2],
                values[3], values[4]));
        saveAndRefresh("Employee added.");
    }

    private void editEmployee() {
        Main.Employee employee = selectedEmployee();
        if (employee == null) return;
        String[] values = prompt("Edit employee",
                new String[]{"Employee ID", "Name", "Designation", "Department", "Contact"},
                new String[]{employee.id(), employee.name(), employee.designation(),
                        employee.department(), employee.contact()});
        if (values == null) return;
        store.employees.put(idKey(employee.id()), new Main.Employee(employee.id(), values[1],
                values[2], values[3], values[4]));
        saveAndRefresh("Employee updated.");
    }

    private void deleteEmployee() {
        Main.Employee employee = selectedEmployee();
        if (employee == null) return;
        if (JOptionPane.showConfirmDialog(frame,
                "Delete " + employee.name() + " and related attendance and leave records?",
                "Confirm deletion", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        store.employees.remove(idKey(employee.id()));
        store.attendance.removeIf(row -> row.employeeId().equalsIgnoreCase(employee.id()));
        store.leaveRequests.removeIf(row -> row.employeeId().equalsIgnoreCase(employee.id()));
        saveAndRefresh("Employee and related records deleted.");
    }

    private void recordAttendance() {
        String[] values = prompt("Record attendance",
                new String[]{"Employee ID", "Date (YYYY-MM-DD)", "Status (PRESENT, ABSENT, ON_LEAVE)"},
                new String[]{"", LocalDate.now().toString(), "PRESENT"});
        if (values == null) return;
        try {
            if (!store.employees.containsKey(idKey(values[0]))) {
                throw new IllegalArgumentException("Employee not found.");
            }
            LocalDate date = LocalDate.parse(values[1]);
            Main.AttendanceStatus attendanceStatus =
                    Main.AttendanceStatus.valueOf(values[2].trim().toUpperCase(Locale.ROOT));
            Main.Attendance record = new Main.Attendance(values[0], date, attendanceStatus);
            int index = -1;
            for (int i = 0; i < store.attendance.size(); i++) {
                Main.Attendance previous = store.attendance.get(i);
                if (previous.employeeId().equalsIgnoreCase(values[0])
                        && previous.date().equals(date)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) store.attendance.add(record);
            else store.attendance.set(index, record);
            saveAndRefresh("Attendance saved.");
        } catch (DateTimeException | IllegalArgumentException e) {
            showError(frame, "Enter a valid employee ID, date, and attendance status.");
        }
    }

    private void addLeave() {
        String[] values = prompt("New leave request", new String[]{
                "Employee ID", "Leave type", "Start date (YYYY-MM-DD)", "End date (YYYY-MM-DD)"});
        if (values == null) return;
        try {
            if (!store.employees.containsKey(idKey(values[0]))) {
                throw new IllegalArgumentException("Employee not found.");
            }
            LocalDate start = LocalDate.parse(values[2]);
            LocalDate end = LocalDate.parse(values[3]);
            if (end.isBefore(start)) throw new IllegalArgumentException("End date must follow start date.");
            store.leaveRequests.add(new Main.LeaveRequest(store.nextLeaveId++, values[0],
                    values[1], start, end, Main.LeaveStatus.PENDING));
            saveAndRefresh("Leave request submitted.");
        } catch (DateTimeException | IllegalArgumentException e) {
            showError(frame, e.getMessage() == null ? "Enter valid dates." : e.getMessage());
        }
    }

    private void decideLeave() {
        int row = leaveTable.getSelectedRow();
        if (row < 0) {
            showError(frame, "Select a leave request first.");
            return;
        }
        long id = Long.parseLong(leaves.getValueAt(leaveTable.convertRowIndexToModel(row), 0)
                .toString());
        for (int i = 0; i < store.leaveRequests.size(); i++) {
            Main.LeaveRequest request = store.leaveRequests.get(i);
            if (request.id() == id) {
                if (request.status() != Main.LeaveStatus.PENDING) {
                    showError(frame, "This request has already been reviewed.");
                    return;
                }
                Object[] options = {"Approve", "Reject", "Cancel"};
                int choice = JOptionPane.showOptionDialog(frame, "Choose a decision for request #"
                                + id, "Review leave request", JOptionPane.DEFAULT_OPTION,
                        JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
                if (choice < 0 || choice > 1) return;
                Main.LeaveStatus result = choice == 0
                        ? Main.LeaveStatus.APPROVED : Main.LeaveStatus.REJECTED;
                store.leaveRequests.set(i, new Main.LeaveRequest(request.id(), request.employeeId(),
                        request.type(), request.startDate(), request.endDate(), result));
                saveAndRefresh("Leave request " + result.name().toLowerCase(Locale.ROOT) + ".");
                return;
            }
        }
        showError(frame, "Leave request not found.");
    }

    private void generateReport() {
        int type = reportType.getSelectedIndex();
        clear(report);
        if (type == 0) {
            report.setColumnIdentifiers(new Object[]{"Employee ID | Name | Designation | Department | Contact"});
            store.employees.values().forEach(employee -> report.addRow(new Object[]{
                    employee.id() + " | " + employee.name() + " | " + employee.designation()
                            + " | " + employee.department() + " | " + employee.contact()}));
        } else if (type == 1) {
            LocalDate start;
            LocalDate end;
            try {
                start = LocalDate.parse(reportStart.getText().trim());
                end = LocalDate.parse(reportEnd.getText().trim());
                if (end.isBefore(start)) throw new IllegalArgumentException("Invalid date range.");
            } catch (DateTimeException | IllegalArgumentException e) {
                showError(frame, "Enter a valid date range in YYYY-MM-DD format.");
                return;
            }
            report.setColumnIdentifiers(new Object[]{"Date | Employee ID | Status"});
            store.attendance.stream()
                    .filter(row -> !row.date().isBefore(start) && !row.date().isAfter(end))
                    .forEach(row -> report.addRow(new Object[]{
                            row.date() + " | " + row.employeeId() + " | " + row.status()}));
        } else {
            report.setColumnIdentifiers(new Object[]{"Request | Employee ID | Type | Dates | Status"});
            store.leaveRequests.forEach(request -> report.addRow(new Object[]{
                    "#" + request.id() + " | " + request.employeeId() + " | " + request.type()
                            + " | " + request.startDate() + " to " + request.endDate()
                            + " | " + request.status()}));
        }
    }

    private void refreshAll() {
        refreshEmployees();
        refreshAttendance();
        refreshLeaves();
        refreshOverview();
        generateReport();
    }

    private void refreshOverview() {
        LocalDate today = LocalDate.now();
        long present = store.attendance.stream().filter(row -> row.date().equals(today)
                && row.status() == Main.AttendanceStatus.PRESENT).count();
        long departments = store.employees.values().stream()
                .map(employee -> employee.department().toLowerCase(Locale.ROOT)).distinct().count();
        long pendingCount = store.leaveRequests.stream()
                .filter(request -> request.status() == Main.LeaveStatus.PENDING).count();
        employeesValue.setText(Integer.toString(store.employees.size()));
        departmentsValue.setText(Long.toString(departments));
        presentValue.setText(Long.toString(present));
        pendingValue.setText(Long.toString(pendingCount));
        clear(pending);
        store.leaveRequests.stream().filter(request -> request.status() == Main.LeaveStatus.PENDING)
                .limit(5).forEach(request -> {
                    Main.Employee employee = store.employees.get(idKey(request.employeeId()));
                    pending.addRow(new Object[]{"#" + request.id(),
                            employee == null ? request.employeeId() : employee.name(),
                            request.type(), request.startDate() + " to " + request.endDate()});
                });
    }

    private void refreshEmployees() {
        clear(employees);
        String query = employeeSearch.getText().trim().toLowerCase(Locale.ROOT);
        store.employees.values().stream()
                .filter(employee -> (employee.id() + " " + employee.name() + " "
                        + employee.designation() + " " + employee.department())
                        .toLowerCase(Locale.ROOT).contains(query))
                .forEach(employee -> employees.addRow(new Object[]{employee.id(), employee.name(),
                        employee.designation(), employee.department(), employee.contact()}));
    }

    private void refreshAttendance() {
        clear(attendance);
        String query = attendanceSearch.getText().trim().toLowerCase(Locale.ROOT);
        store.attendance.stream()
                .filter(record -> (record.employeeId() + " " + employeeName(record.employeeId())
                        + " " + record.date() + " " + record.status())
                        .toLowerCase(Locale.ROOT).contains(query))
                .forEach(record -> attendance.addRow(new Object[]{record.employeeId(),
                        employeeName(record.employeeId()), record.date(), record.status()}));
    }

    private void refreshLeaves() {
        clear(leaves);
        store.leaveRequests.forEach(request -> leaves.addRow(new Object[]{request.id(),
                request.employeeId(), employeeName(request.employeeId()), request.type(),
                request.startDate(), request.endDate(), request.status()}));
    }

    private Main.Employee selectedEmployee() {
        int row = employeeTable.getSelectedRow();
        if (row < 0) {
            showError(frame, "Select an employee first.");
            return null;
        }
        String id = employees.getValueAt(employeeTable.convertRowIndexToModel(row), 0).toString();
        return store.employees.get(idKey(id));
    }

    private String employeeName(String id) {
        Main.Employee employee = store.employees.get(idKey(id));
        return employee == null ? id : employee.name();
    }

    private void saveAndRefresh(String message) {
        try {
            store.save();
            refreshAll();
            status.setText(message);
        } catch (IOException e) {
            showError(frame, "Unable to save HRMS data: " + e.getMessage());
            status.setText("Save failed.");
        }
    }

    private static String[] prompt(String title, String[] labels) {
        return prompt(title, labels, new String[labels.length]);
    }

    private static String[] prompt(String title, String[] labels, String[] defaults) {
        JTextField[] inputs = new JTextField[labels.length];
        Component[] components = new Component[labels.length];
        for (int i = 0; i < labels.length; i++) {
            inputs[i] = new JTextField(defaults[i], 22);
            components[i] = inputs[i];
        }
        JPanel fields = form(labels, components);
        while (JOptionPane.showConfirmDialog(null, fields, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            String[] values = new String[inputs.length];
            boolean valid = true;
            for (int i = 0; i < inputs.length; i++) {
                values[i] = inputs[i].getText().trim();
                valid &= !values[i].isEmpty();
            }
            if (valid) return values;
            showError(null, "Please complete every field.");
        }
        return null;
    }

    private static JPanel form(String[] labels, Component[] inputs) {
        JPanel panel = new JPanel(new GridLayout(labels.length, 2, 9, 9));
        for (int i = 0; i < labels.length; i++) {
            panel.add(new JLabel(labels[i]));
            panel.add(inputs[i]);
        }
        return panel;
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static void configure(JTable table) {
        table.setFillsViewportHeight(true);
        table.setRowHeight(25);
        table.setAutoCreateRowSorter(true);
    }

    private static void clear(DefaultTableModel model) {
        model.setRowCount(0);
    }

    private static String idKey(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    private static void showError(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "HRMS", JOptionPane.ERROR_MESSAGE);
    }

    private static javax.swing.event.DocumentListener documentListener(Runnable action) {
        return new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                action.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                action.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                action.run();
            }
        };
    }
}
