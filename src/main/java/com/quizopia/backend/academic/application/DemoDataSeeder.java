package com.quizopia.backend.academic.application;

import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.classroom.domain.model.Classroom;
import com.quizopia.backend.classroom.domain.model.ClassroomMember;
import com.quizopia.backend.classroom.repository.ClassroomMemberRepository;
import com.quizopia.backend.classroom.repository.ClassroomRepository;
import com.quizopia.backend.exam.domain.model.ExamPurpose;
import com.quizopia.backend.exam.repository.ExamPurposeRepository;
import com.quizopia.backend.identity.domain.model.Role;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserRole;
import com.quizopia.backend.identity.repository.RoleRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import com.quizopia.backend.security.password.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal, idempotent seeder that provisions the global academic catalog
 * (grade levels, subjects, exam purposes), demo accounts (1 teacher + 3
 * students) and a user-owned demo classroom (with the students as members)
 * so the end-to-end class-based flow can be exercised without any onboarding
 * step. User-centric model: no school, no workspace, no profiles.
 *
 * <p>Runs only when {@code quizopia.demo.data.enabled=true} (bound from
 * {@code QUIZOPIA_DEMO_DATA_ENABLED}, default {@code false}). It never runs in
 * production unless the flag is explicitly enabled. The demo account passwords
 * below are DEV-ONLY fixtures (gated behind the flag); they are NOT production
 * secrets and must not be reused elsewhere.
 *
 * <p>Idempotency relies on case-insensitive unique business codes/usernames plus
 * a transactional {@code find-or-create} per row, so concurrent or repeated
 * startups cannot create duplicates.
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    /** Demo classroom code (owner = the demo teacher user). */
    public static final String DEMO_CLASSROOM_CODE = "DEMO-CLASS";

    /** DEV-ONLY password for all demo accounts (gated by demo flag). */
    public static final String DEMO_PASSWORD = "Demo@12345";

    /** Real Vietnamese high-school grade levels: {code, name, sort_order}. */
    private static final String[][] REAL_GRADE_LEVELS = {
            {"G10", "Grade 10", "10"},
            {"G11", "Grade 11", "11"},
            {"G12", "Grade 12", "12"}
    };
    private static final List<String> REAL_GRADE_CODES = List.of("G10", "G11", "G12");

    /** Real Vietnamese curriculum subjects: {code, name}. Created for every grade level. */
    private static final String[][] REAL_SUBJECTS = {
            {"VAN", "Ngữ văn"},
            {"TOAN", "Toán học"},
            {"NGOAI_NGU", "Ngoại ngữ"},
            {"GDTC", "Giáo dục thể chất"},
            {"GDQP", "Giáo dục quốc phòng và an ninh"},
            {"GDKT_PL", "Giáo dục kinh tế và pháp luật"},
            {"HDTN_HN", "Hoạt động trải nghiệm, hướng nghiệp"},
            {"LY", "Vật lý"},
            {"HOA", "Hóa học"},
            {"SINH", "Sinh học"},
            {"SU", "Lịch sử"},
            {"DIA", "Địa lý"},
            {"GDCD", "Giáo dục công dân"},
            {"TIN", "Tin học"},
            {"CN", "Công nghệ"}
    };

    private static final String DEMO_TEACHER_USERNAME = "demo-teacher";
    private static final List<String> DEMO_STUDENT_USERNAMES =
            List.of("demo-student-1", "demo-student-2", "demo-student-3");
    private static final String TEACHER_ROLE_CODE = "TEACHER";
    private static final String STUDENT_ROLE_CODE = "STUDENT";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final GradeLevelRepository gradeLevelRepository;
    private final SubjectRepository subjectRepository;
    private final ExamPurposeRepository examPurposeRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PasswordHasher passwordHasher;
    private final ClassroomRepository classroomRepository;
    private final ClassroomMemberRepository classroomMemberRepository;
    private final boolean demoEnabled;

    public DemoDataSeeder(GradeLevelRepository gradeLevelRepository,
            SubjectRepository subjectRepository,
            ExamPurposeRepository examPurposeRepository,
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleRepository roleRepository,
            PasswordHasher passwordHasher,
            ClassroomRepository classroomRepository,
            ClassroomMemberRepository classroomMemberRepository,
            @Value("${quizopia.demo.data.enabled:false}") boolean demoEnabled) {
        this.gradeLevelRepository = gradeLevelRepository;
        this.subjectRepository = subjectRepository;
        this.examPurposeRepository = examPurposeRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.passwordHasher = passwordHasher;
        this.classroomRepository = classroomRepository;
        this.classroomMemberRepository = classroomMemberRepository;
        this.demoEnabled = demoEnabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!demoEnabled) {
            return;
        }
        seed();
    }

    private void seed() {
        seedGlobalCatalog();
        seedDemoClassroom();
        log.info("Demo data ensured (classroom={}, grades={}, subjects={})",
                DEMO_CLASSROOM_CODE,
                REAL_GRADE_LEVELS.length, REAL_GRADE_LEVELS.length * REAL_SUBJECTS.length);
    }

    /**
     * Ensures the global academic catalog (V24 model): 3 grade levels, 15
     * subjects per grade, 4 exam purposes. Find-or-create per row; on an
     * upgraded database the migration's deduped catalog is reused as-is.
     */
    private void seedGlobalCatalog() {
        Map<String, Long> gradeIdsByCode = new LinkedHashMap<>();
        for (String[] g : REAL_GRADE_LEVELS) {
            GradeLevel gradeLevel = gradeLevelRepository.findByCodeIgnoreCase(g[0])
                    .orElseGet(() -> {
                        GradeLevel created = new GradeLevel(g[0], g[1]);
                        created.setSortOrder(Integer.parseInt(g[2]));
                        return gradeLevelRepository.saveAndFlush(created);
                    });
            gradeIdsByCode.put(g[0], gradeLevel.getId());
        }

        for (String gradeCode : REAL_GRADE_CODES) {
            Long gradeLevelId = gradeIdsByCode.get(gradeCode);
            for (String[] s : REAL_SUBJECTS) {
                subjectRepository.findByGradeLevelIdAndCodeIgnoreCase(gradeLevelId, s[0])
                        .orElseGet(() -> subjectRepository.saveAndFlush(
                                new Subject(gradeLevelId, s[0], s[1])));
            }
        }
        seedExamPurposes();
    }

    /** Idempotently ensures the 4 default exam purposes (global catalog). */
    private void seedExamPurposes() {
        String[][] purposes = {
                {"MIDTERM", "Giữa kỳ", "0"},
                {"FINAL", "Cuối kỳ", "1"},
                {"QUIZ", "Bài kiểm tra", "2"},
                {"PRACTICE", "Luyện tập", "3"}
        };
        for (String[] p : purposes) {
            boolean exists = examPurposeRepository.findAllByOrderByPositionAsc().stream()
                    .anyMatch(ep -> ep.getCode().equalsIgnoreCase(p[0]));
            if (!exists) {
                ExamPurpose purpose = new ExamPurpose(p[0], p[1]);
                purpose.setPosition(Integer.parseInt(p[2]));
                examPurposeRepository.saveAndFlush(purpose);
            }
        }
    }

    /**
     * Idempotently ensures: 1 demo TEACHER, 3 demo STUDENTS, and one demo
     * classroom owned by the teacher with the 3 students as members — so the
     * class-based session-visibility flow is exercisable end-to-end (create
     * classroom → add members → create CLASS_RESTRICTED session → assign class
     * → students see + start).
     */
    private void seedDemoClassroom() {
        Long teacherUserId = ensureDemoUser(DEMO_TEACHER_USERNAME, TEACHER_ROLE_CODE, "Demo Teacher");

        Classroom classroom = classroomRepository
                .findByOwnerUserIdAndCodeIgnoreCase(teacherUserId, DEMO_CLASSROOM_CODE)
                .orElseGet(() -> classroomRepository.saveAndFlush(
                        new Classroom(teacherUserId, DEMO_CLASSROOM_CODE, "Demo Classroom")));

        for (String username : DEMO_STUDENT_USERNAMES) {
            Long studentUserId = ensureDemoUser(username, STUDENT_ROLE_CODE,
                    username.replace('-', ' ').replace("student", "Student"));
            if (!classroomMemberRepository.existsByClassroomIdAndUserId(classroom.getId(), studentUserId)) {
                classroomMemberRepository.saveAndFlush(new ClassroomMember(classroom.getId(), studentUserId));
            }
        }
    }

    /** Find-or-create a demo user + role assignment. Returns userId. */
    private Long ensureDemoUser(String username, String roleCode, String displayName) {
        User user = userRepository.findByUsernameIgnoreCase(username).orElseGet(() -> {
            String hash = passwordHasher.hash(DEMO_PASSWORD);
            User u = new User(username, username + "@demo.quizopia.local", hash, displayName);
            u.setLocalCredentialState(com.quizopia.backend.identity.domain.model.LocalCredentialState.ENABLED);
            // Operator-seeded demo accounts are trusted: verified by seeding.
            u.setEmailVerifiedAt(java.time.Instant.now());
            // Demo accounts are operator-seeded and password-authenticated:
            // no OTP gate, and seeding itself asserts the address.
            u.setLoginRequiresEmailVerification(Boolean.FALSE);
            return userRepository.saveAndFlush(u);
        });
        // Role assignment (idempotent via find — user_roles PK is (user_id, role_id)).
        if (userRoleRepository.findActiveRoleCodesByUserId(user.getId(), java.time.Instant.now()).isEmpty()) {
            Role role = roleRepository.findByCode(roleCode).orElseThrow(() -> new IllegalStateException(
                    "Required role '" + roleCode + "' is not seeded; check Flyway V3"));
            userRoleRepository.saveAndFlush(new UserRole(user, role, null, null));
        }
        return user.getId();
    }
}
