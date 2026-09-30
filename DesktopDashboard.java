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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.FlowLayout;
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
    private final JLabel employeeCount = new JLabel();
    private final JLabel departmentCount = new JLabel();
    private final JLabel attendanceCount = new JLabel();
    private final JLabel pendingCount = new JLabel();
    private final DefaultTableModel employeeModel = tableModel(
            "Employee ID", "Name", "Designation", "Department", "Contact");
    private final DefaultTableModel attendanceModel = tableModel(
            "Employee ID", "Date", "Status");
    private final DefaultTableModel leaveModel = tableModel(
            "Request ID", "Employee ID", "Employee", "Type", "Start", "End", "Status");
    private final DefaultTableModel reportModel = tableModel("Report");
    private final DefaultTableModel pendingModel = tableModel(
            "Request", "Employee", "Leave type", "Dates");
    private final JTable employeeTable = new JTable(employeeModel);
    private final JTable attendanceTable = new JTable(attendanceModel);
    private final JTable leaveTable = new JTable(leaveModel);
    private final JTable reportTable = new JTable(reportModel);
    private final JTextField employeeSearch = new JTextField(22);
    private final JTextField attendanceSearch = new JTextField(22);
    private final JTextField reportStart = new JTextField(LocalDate.now().withDayOfMonth(1).toString(), 10);
    private final JTextField reportEnd = new JTextField(LocalDate.now().toString(), 10);
    private final JComboBox<String> reportType = new JComboBox<>(
            new String[]{"Employee directory", "Attendance date range", "Leave summary"});

    private DesktopDashboard(Main.Store store) {
        this.store = store;
    }

    static void start(Main.Store store) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                if (store.admin == null && !setupAdministrator(store)) {
                    return;
                }
                if (authenticate(store)) {
                    new DesktopDashboard(store).show();
                }
            } catch (IOException | GeneralSecurityException e) {
                showError(null, "Unable to start HRMS: " + e.getMessage());
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                     | javax.swing.UnsupportedLookAndFeelException e) {
                showError(null, "Unable to initialize the desktop interface: " + e.getMessage());
            }
        });
    }

    private static boolean setupAdministrator(Main.Store store)
            throws IOException, GeneralSecurityException {
        JTextField username = new JTextField(20);
        JPasswordField password = new JPasswordField(20);
        JPasswordField confirmation = new JPasswordField(20);
        JPanel form = formPanel(
                new String[]{"Administrator username", "Password (10+ characters)", "Confirm password"},
                new Component[]{username, password, confirmation});
        while (true) {
            int result = JOptionPane.showConfirmDialog(null, form,
                    "First-time administrator setup", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return false;
            }
            char[] entered = password.getPassword();
            char[] confirmed = confirmation.getPassword();
            try {
                if (username.getText().isBlank()) {
                    showError(null, "Administrator username cannot be empty.");
                } else if (entered.length < 10) {
                    showError(null, "Choose a password with at least 10 characters.");
                } else if (!Arrays.equals(entered, confirmed)) {
                    showError(null, "The passwords do not match.");
                } else {
                    Main.createAdministrator(store, username.getText(), entered);
                    return true;
                }
            } finally {
                Arrays.fill(entered, '\0');
                Arrays.fill(confirmed, '\0');
                password.setText("");
                confirmation.setText("");
            }
        }
    }

    private static boolean authenticate(Main.Store store) throws GeneralSecurityException {
        for (int attempt = 1; attempt <= 5; attempt++) {
            JTextField username = new JTextField(store.admin.username(), 20);
            JPasswordField password = new JPasswordField(20);
            JPanel form = formPanel(new String[]{"Username", "Password"},
                    new Component[]{username, password});
            int result = JOptionPane.showConfirmDialog(null, form, "HRMS administrator sign in",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return false;
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
                return true;
            }
            showError(null, "Incorrect username or password. "
                    + (5 - attempt) + " attempt(s) remaining.");
        }
        showError(null, "Too many failed sign-in attempts.");
        return false;
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 620));
        frame.setSize(1100, 720);
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());
        frame.add(header(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Overview", overviewPanel());
        tabs.addTab("Employees", employeesPanel());
        tabs.addTab("Attendance", attendancePanel());
        tabs.addTab("Leave", leavePanel());
        tabs.addTab("Reports", reportsPanel());
        frame.add(tabs, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        status.setForeground(MUTED);
        frame.add(status, BorderLayout.SOUTH);
        refreshAll();
        frame.setVisible(true);
    }

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(NAVY);
        header.setBorder(BorderFactory.createEmptyBorder(16, 22, 16, 22));
        JLabel title = new JLabel("HRMS  |  People Operations");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 21f));
        JLabel user = new JLabel("Administrator: " + store.admin.username());
        user.setForeground(new Color(220, 230, 243));
        header.add(title, BorderLayout.WEST);
        header.add(user, BorderLayout.EAST);
        return header;
    }

    private JPanel overviewPanel() {
        JPanel panel = new JPanel(new BorderLayout(16, 16));
        panel.setBorder(BorderFactory.createEmptyBorder(22, 22, 22, 22));
        JPanel cards = new JPanel(new GridLayout(1, 4, 12, 12));
        cards.add(card("EMPLOYEES", employeeCount));
        cards.add(card("DEPARTMENTS", departmentCount));
        cards.add(card("PRESENT TODAY", attendanceCount));
        cards.add(card("PENDING LEAVE", pendingCount));

        JTable pendingTable = new JTable(pendingModel);
        panel.add(cards, BorderLayout.NORTH);
        panel.add(new JScrollPane(pendingTable), BorderLayout.CENTER);
        panel.add(new JLabel("Pending leave requests"), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel employeesPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tools.add(new JLabel("Search employees"));
        tools.add(employeeSearch);
        tools.add(button("Add employee", this::addEmployee));
        tools.add(button("Edit selected", this::editEmployee));
        tools.add(button("Delete selected", this::deleteEmployee));
        employeeSearch.getDocument().addDocumentListener(listener(this::refreshEmployees));
        configureTable(employeeTable);
        panel.add(tools, BorderLayout.NORTH);
        panel.add(new JScrollPane(employeeTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel attendancePanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tools.add(new JLabel("Filter"));
        tools.add(attendanceSearch);
        tools.add(button("Record attendance", this::recordAttendance));
        attendanceSearch.getDocument().addDocumentListener(listener(this::refreshAttendance));
        configureTable(attendanceTable);
        panel.add(tools, BorderLayout.NORTH);
        panel.add(new JScrollPane(attendanceTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel leavePanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tools.add(button("New leave request", this::addLeaveRequest));
        tools.add(button("Approve / reject selected", this::decideLeave));
        configureTable(leaveTable);
        panel.add(tools, BorderLayout.NORTH);
        panel.add(new JScrollPane(leaveTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel reportsPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tools.add(reportType);
        tools.add(new JLabel("From (YYYY-MM-DD)"));
        tools.add(reportStart);
        tools.add(new JLabel("To"));
        tools.add(reportEnd);
        tools.add(button("Generate report", this::generateReport));
        configureTable(reportTable);
        panel.add(tools, BorderLayout.NORTH);
        panel.add(new JScrollPane(reportTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel card(String heading, JLabel value) {
        JPanel card = new JPanel(new BorderLayout(4, 8));
        card.setBackground(new Color(245, 248, 252));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(222, 230, 240)),
                BorderFactory.createEmptyBorder(14, 14, 14, 14)));
        JLabel title = new JLabel(heading);
        title.setForeground(MUTED);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));
        value.setForeground(BLUE);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 26f));
        card.add(title, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        return card;
    }

    private void addEmployee() {
        String[] values = prompt("Add employee", new String[]{
                "Employee ID", "Name", "Designation", "Department", "Contact information"});
        if (values == null) {
            return;
        }
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
        Main.Employee existing = selectedEmployee();
        if (existing == null) {
            return;
        }
        String[] values = prompt("Edit employee", new String[]{
                "Employee ID", "Name", "Designation", "Department", "Contact information"},
                new String[]{existing.id(), existing.name(), existing.designation(),
                        existing.department(), existing.contact()});
        if (values == null) {
            return;
        }
        store.employees.put(idKey(existing.id()), new Main.Employee(existing.id(), values[1],
                values[2], values[3], values[4]));
        saveAndRefresh("Employee updated.");
    }

    private void deleteEmployee() {
        Main.Employee employee = selectedEmployee();
        if (employee == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(frame,
                "Delete " + employee.name() + " and all related attendance and leave records?",
                "Confirm employee deletion", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        store.employees.remove(idKey(employee.id()));
        store.attendance.removeIf(record -> record.employeeId().equalsIgnoreCase(employee.id()));
        store.leaveRequests.removeIf(request -> request.employeeId().equalsIgnoreCase(employee.id()));
        saveAndRefresh("Employee and related records deleted.");
    }

    private void recordAttendance() {
        String[] values = prompt("Record attendance",
                new String[]{"Employee ID", "Date (YYYY-MM-DD)", "Status: PRESENT, ABSENT, ON_LEAVE"},
                new String[]{"", LocalDate.now().toString(), "PRESENT"});
        if (values == null) {
            return;
        }
        try {
            if (!store.employees.containsKey(idKey(values[0]))) {
                throw new IllegalArgumentException("Employee ID was not found.");
            }
            LocalDate date = LocalDate.parse(values[1]);
            Main.AttendanceStatus attendanceStatus =
                    Main.AttendanceStatus.valueOf(values[2].trim().toUpperCase(Locale.ROOT));
            Main.Attendance record = new Main.Attendance(values[0], date, attendanceStatus);
            int existing = -1;
            for (int i = 0; i < store.attendance.size(); i++) {
                Main.Attendance current = store.attendance.get(i);
                if (current.employeeId().equalsIgnoreCase(values[0]) && current.date().equals(date)) {
                    existing = i;
                    break;
                }
            }
            if (existing < 0) {
                store.attendance.add(record);
            } else {
                store.attendance.set(existing, record);
            }
            saveAndRefresh("Attendance saved.");
        } catch (DateTimeException | IllegalArgumentException e) {
            showError(frame, "Enter a valid employee ID, date, and attendance status.");
        }
    }

    private void addLeaveRequest() {
        String[] values = prompt("New leave request", new String[]{
                "Employee ID", "Leave type", "Start date (YYYY-MM-DD)", "End date (YYYY-MM-DD)"});
        if (values == null) {
            return;
        }
        try {
            if (!store.employees.containsKey(idKey(values[0]))) {
                throw new IllegalArgumentException("Employee ID was not found.");
            }
            LocalDate start = LocalDate.parse(values[2]);
            LocalDate end = LocalDate.parse(values[3]);
            if (end.isBefore(start)) {
                throw new IllegalArgumentException("End date cannot be before start date.");
            }
            store.leaveRequests.add(new Main.LeaveRequest(store.nextLeaveId++, values[0],
                    values[1], start, end, Main.LeaveStatus.PENDING));
            saveAndRefresh("Leave request submitted.");
        } catch (DateTimeException | IllegalArgumentException e) {
            showError(frame, e.getMessage() == null
                    ? "Enter valid dates in YYYY-MM-DD format." : e.getMessage());
        }
    }

    private void decideLeave() {
        int row = leaveTable.getSelectedRow();
        if (row < 0) {
            showError(frame, "Select a leave request first.");
            return;
        }
        long requestId = Long.parseLong(leaveModel.getValueAt(
                leaveTable.convertRowIndexToModel(row), 0).toString());
        int index = -1;
        for (int i = 0; i < store.leaveRequests.size(); i++) {
            if (store.leaveRequests.get(i).id() == requestId) {
                index = i;
                break;
            }
        }
        if (index < 0 || store.leaveRequests.get(index).status() != Main.LeaveStatus.PENDING) {
            showError(frame, "Only pending leave requests can be reviewed.");
            return;
        }
        Object[] options = {"Approve", "Reject", "Cancel"};
        int choice = JOptionPane.showOptionDialog(frame, "Choose a decision for request #"
                        + requestId, "Review leave request", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice < 0 || choice > 1) {
            return;
        }
        Main.LeaveRequest old = store.leaveRequests.get(index);
        Main.LeaveStatus decision = choice == 0
                ? Main.LeaveStatus.APPROVED : Main.LeaveStatus.REJECTED;
        store.leaveRequests.set(index, new Main.LeaveRequest(old.id(), old.employeeId(),
                old.type(), old.startDate(), old.endDate(), decision));
        saveAndRefresh("Leave request " + decision.name().toLowerCase(Locale.ROOT) + ".");
    }

    private void generateReport() {
        int choice = reportType.getSelectedIndex();
        if (choice == 1) {
            LocalDate start;
            LocalDate end;
            try {
                start = LocalDate.parse(reportStart.getText().trim());
                end = LocalDate.parse(reportEnd.getText().trim());
                if (end.isBefore(start)) {
                    throw new IllegalArgumentException();
                }
            } catch (DateTimeException | IllegalArgumentException e) {
                showError(frame, "Enter a valid date range in YYYY-MM-DD format.");
                return;
            }
            setReportColumn("Date | Employee ID | Status");
            for (Main.Attendance record : store.attendance) {
                if (!record.date().isBefore(start) && !record.date().isAfter(end)) {
                    reportModel.addRow(new Object[]{record.date() + " | "
                            + record.employeeId() + " | " + record.status()});
                }
            }
        } else if (choice == 0) {
            setReportColumn("Employee ID | Name | Designation | Department | Contact");
            store.employees.values().forEach(employee -> reportModel.addRow(new Object[]{
                    employee.id() + " | " + employee.name() + " | " + employee.designation()
                            + " | " + employee.department() + " | " + employee.contact()}));
        } else {
            setReportColumn("Request | Employee | Type | Dates | Status");
            store.leaveRequests.forEach(request -> reportModel.addRow(new Object[]{
                    "#" + request.id() + " | " + request.employeeId() + " | " + request.type()
                            + " | " + request.startDate() + " to " + request.endDate()
                            + " | " + request.status()}));
        }
        status.setText("Report generated: " + reportType.getSelectedItem());
    }

    private void refreshAll() {
        refreshEmployees();
        refreshAttendance();
        refreshLeaves();
        refreshOverview();
        generateReport();
    }

    private void refreshEmployees() {
        clear(employeeModel);
        String query = employeeSearch.getText().trim().toLowerCase(Locale.ROOT);
        store.employees.values().stream()
                .filter(employee -> (employee.id() + " " + employee.name() + " "
                        + employee.designation() + " " + employee.department() + " "
                        + employee.contact()).toLowerCase(Locale.ROOT).contains(query))
                .forEach(employee -> employeeModel.addRow(new Object[]{employee.id(),
                        employee.name(), employee.designation(), employee.department(),
                        employee.contact()}));
    }

    private void refreshAttendance() {
        clear(attendanceModel);
        String query = attendanceSearch.getText().trim().toLowerCase(Locale.ROOT);
        store.attendance.stream()
                .filter(record -> (record.employeeId() + " " + record.date() + " "
                        + record.status()).toLowerCase(Locale.ROOT).contains(query))
                .forEach(record -> attendanceModel.addRow(new Object[]{record.employeeId(),
                        record.date(), record.status()}));
    }

    private void refreshLeaves() {
        clear(leaveModel);
        store.leaveRequests.forEach(request -> {
            Main.Employee employee = store.employees.get(idKey(request.employeeId()));
            leaveModel.addRow(new Object[]{request.id(), request.employeeId(),
                    employee == null ? request.employeeId() : employee.name(), request.type(),
                    request.startDate(), request.endDate(), request.status()});
        });
    }

    private void refreshOverview() {
        LocalDate today = LocalDate.now();
        long present = store.attendance.stream().filter(record -> record.date().equals(today)
                && record.status() == Main.AttendanceStatus.PRESENT).count();
        long pending = store.leaveRequests.stream()
                .filter(request -> request.status() == Main.LeaveStatus.PENDING).count();
        long departments = store.employees.values().stream()
                .map(employee -> employee.department().toLowerCase(Locale.ROOT)).distinct().count();
        employeeCount.setText(Integer.toString(store.employees.size()));
        departmentCount.setText(Long.toString(departments));
        attendanceCount.setText(Long.toString(present));
        pendingCount.setText(Long.toString(pending));

        clear(pendingModel);
        store.leaveRequests.stream().filter(request -> request.status() == Main.LeaveStatus.PENDING)
                .forEach(request -> {
                    Main.Employee employee = store.employees.get(idKey(request.employeeId()));
                    pendingModel.addRow(new Object[]{"#" + request.id(),
                            employee == null ? request.employeeId() : employee.name(), request.type(),
                            request.startDate() + " to " + request.endDate()});
                });
    }

    private Main.Employee selectedEmployee() {
        int row = employeeTable.getSelectedRow();
        if (row < 0) {
            showError(frame, "Select an employee first.");
            return null;
        }
        String id = employeeModel.getValueAt(employeeTable.convertRowIndexToModel(row), 0).toString();
        return store.employees.get(idKey(id));
    }

    private void saveAndRefresh(String message) {
        try {
            store.save();
            refreshAll();
            status.setText(message);
        } catch (IOException e) {
            showError(frame, "Unable to save HRMS data: " + e.getMessage());
            status.setText("Save failed. Changes are not safely persisted.");
        }
    }

    private static String[] prompt(String title, String[] labels) {
        return prompt(title, labels, new String[labels.length]);
    }

    private static String[] prompt(String title, String[] labels, String[] initialValues) {
        JTextField[] inputs = new JTextField[labels.length];
        Component[] components = new Component[labels.length];
        for (int i = 0; i < labels.length; i++) {
            inputs[i] = new JTextField(initialValues[i], 22);
            components[i] = inputs[i];
        }
        JPanel panel = formPanel(labels, components);
        while (JOptionPane.showConfirmDialog(null, panel, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            String[] values = new String[inputs.length];
            boolean complete = true;
            for (int i = 0; i < inputs.length; i++) {
                values[i] = inputs[i].getText().trim();
                complete &= !values[i].isEmpty();
            }
            if (complete) {
                return values;
            }
            showError(null, "Please complete every field.");
        }
        return null;
    }

    private static JPanel formPanel(String[] labels, Component[] inputs) {
        JPanel panel = new JPanel(new GridLayout(labels.length, 2, 10, 10));
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

    private static DefaultTableModel tableModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static void configureTable(JTable table) {
        table.setFillsViewportHeight(true);
        table.setRowHeight(26);
        table.setAutoCreateRowSorter(true);
    }

    private static void clear(DefaultTableModel model) {
        model.setRowCount(0);
    }

    private static void showError(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "HRMS", JOptionPane.ERROR_MESSAGE);
    }

    private static String idKey(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    private static DocumentListener listener(Runnable update) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                update.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                update.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                update.run();
            }
        };
    }

    private void setReportColumn(String column) {
        clear(reportModel);
        reportModel.setColumnIdentifiers(new Object[]{column});
    }
}
