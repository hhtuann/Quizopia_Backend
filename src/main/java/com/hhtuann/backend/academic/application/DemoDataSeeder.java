package com.hhtuann.backend.academic.application;

import com.hhtuann.backend.academic.domain.model.GradeLevel;
import com.hhtuann.backend.academic.domain.model.School;
import com.hhtuann.backend.academic.domain.model.StudentProfile;
import com.hhtuann.backend.academic.domain.model.Subject;
import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.repository.GradeLevelRepository;
import com.hhtuann.backend.academic.repository.SchoolRepository;
import com.hhtuann.backend.academic.repository.StudentProfileRepository;
import com.hhtuann.backend.academic.repository.SubjectRepository;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
import com.hhtuann.backend.classroom.domain.model.Classroom;
import com.hhtuann.backend.classroom.domain.model.ClassroomMember;
import com.hhtuann.backend.classroom.repository.ClassroomMemberRepository;
import com.hhtuann.backend.classroom.repository.ClassroomRepository;
import com.hhtuann.backend.exam.domain.model.ExamPurpose;
import com.hhtuann.backend.exam.repository.ExamPurposeRepository;
import com.hhtuann.backend.identity.domain.model.Role;
import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.identity.domain.model.UserRole;
import com.hhtuann.backend.identity.repository.RoleRepository;
import com.hhtuann.backend.identity.repository.UserRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import com.hhtuann.backend.security.password.PasswordHasher;
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
 * Internal, idempotent seeder that provisions a single demo school, grade
 * level, subject, exam purposes, demo accounts (1 teacher + 3 students) and a
 * demo classroom (with the students as members) so the end-to-end class-based
 * MVP flow can be exercised without a production onboarding step.
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

    /** Business code of the single demo school. Shared with registration. */
    public static final String DEMO_SCHOOL_CODE = "DEMO-SCHOOL";

    /** Business code of the single demo grade level within the demo school. */
    public static final String DEMO_GRADE_LEVEL_CODE = "DEMO-GL-12";

    /** Business code of the single demo subject within the demo school. */
    public static final String DEMO_SUBJECT_CODE = "DEMO-SUBJECT";

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

    /** Demo classroom code (owner = the demo teacher). */
    public static final String DEMO_CLASSROOM_CODE = "DEMO-CLASS";

    /** DEV-ONLY password for all demo accounts (gated by demo flag). */
    public static final String DEMO_PASSWORD = "Demo@12345";

    private static final String DEMO_TEACHER_USERNAME = "demo-teacher";
    private static final List<String> DEMO_STUDENT_USERNAMES =
            List.of("demo-student-1", "demo-student-2", "demo-student-3");
    private static final String TEACHER_ROLE_CODE = "TEACHER";
    private static final String STUDENT_ROLE_CODE = "STUDENT";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final SchoolRepository schoolRepository;
    private final GradeLevelRepository gradeLevelRepository;
    private final SubjectRepository subjectRepository;
    private final ExamPurposeRepository examPurposeRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PasswordHasher passwordHasher;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final ClassroomRepository classroomRepository;
    private final ClassroomMemberRepository classroomMemberRepository;
    private final boolean demoEnabled;

    public DemoDataSeeder(SchoolRepository schoolRepository,
            GradeLevelRepository gradeLevelRepository,
            SubjectRepository subjectRepository,
            ExamPurposeRepository examPurposeRepository,
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleRepository roleRepository,
            PasswordHasher passwordHasher,
            TeacherProfileRepository teacherProfileRepository,
            StudentProfileRepository studentProfileRepository,
            ClassroomRepository classroomRepository,
            ClassroomMemberRepository classroomMemberRepository,
            @Value("${quizopia.demo.data.enabled:false}") boolean demoEnabled) {
        this.schoolRepository = schoolRepository;
        this.gradeLevelRepository = gradeLevelRepository;
        this.subjectRepository = subjectRepository;
        this.examPurposeRepository = examPurposeRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.passwordHasher = passwordHasher;
        this.teacherProfileRepository = teacherProfileRepository;
        this.studentProfileRepository = studentProfileRepository;
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
        School school = schoolRepository.findByCodeIgnoreCase(DEMO_SCHOOL_CODE)
                .orElseGet(() -> schoolRepository.saveAndFlush(
                        new School(DEMO_SCHOOL_CODE, "Quizopia Demo School")));
        Long schoolId = school.getId();

        // Retire the legacy single demo grade level + subject (no-op while still referenced).
        cleanupLegacyDemoRows(schoolId);

        // 3 real grade levels (Vietnamese high school): Grade 10 / 11 / 12.
        Map<String, Long> gradeIdsByCode = new LinkedHashMap<>();
        for (String[] g : REAL_GRADE_LEVELS) {
            GradeLevel gradeLevel = gradeLevelRepository.findBySchoolIdAndCodeIgnoreCase(schoolId, g[0])
                    .orElseGet(() -> {
                        GradeLevel created = new GradeLevel(schoolId, g[0], g[1]);
                        created.setSortOrder(Integer.parseInt(g[2]));
                        return gradeLevelRepository.saveAndFlush(created);
                    });
            gradeIdsByCode.put(g[0], gradeLevel.getId());
        }

        // 15 real subjects, each created for every grade level (15 x 3 = 45 rows).
        for (String gradeCode : REAL_GRADE_CODES) {
            Long gradeLevelId = gradeIdsByCode.get(gradeCode);
            for (String[] s : REAL_SUBJECTS) {
                subjectRepository.findBySchoolIdAndGradeLevelIdAndCodeIgnoreCase(schoolId, gradeLevelId, s[0])
                        .orElseGet(() -> subjectRepository.saveAndFlush(
                                new Subject(schoolId, gradeLevelId, s[0], s[1])));
            }
        }

        seedExamPurposes(schoolId);
        seedDemoClassroom(schoolId);

        log.info("Demo academic + classroom data ensured (school={}, classroom={}, grades={}, subjects={})",
                DEMO_SCHOOL_CODE, DEMO_CLASSROOM_CODE,
                REAL_GRADE_LEVELS.length, REAL_GRADE_LEVELS.length * REAL_SUBJECTS.length);
    }

    /**
     * Best-effort removal of the legacy single demo grade level and subject that
     * earlier seeder versions created. Both deletes are conditional on no
     * remaining references (ON DELETE RESTRICT), so this is a no-op while either
     * row is still in use and never throws — the real-data seed always commits.
     */
    private void cleanupLegacyDemoRows(Long schoolId) {
        gradeLevelRepository.findBySchoolIdAndCodeIgnoreCase(schoolId, DEMO_GRADE_LEVEL_CODE)
                .ifPresent(gradeLevel -> {
                    subjectRepository.findBySchoolIdAndGradeLevelIdAndCodeIgnoreCase(
                                    schoolId, gradeLevel.getId(), DEMO_SUBJECT_CODE)
                            .ifPresent(subject -> subjectRepository.deleteIfUnreferenced(subject.getId()));
                    gradeLevelRepository.deleteIfNoSubjects(gradeLevel.getId());
                });
    }

    /**
     * Idempotently ensures the 4 default exam purposes for the demo school.
     */
    private void seedExamPurposes(Long schoolId) {
        String[][] purposes = {
                {"MIDTERM", "Giữa kỳ", "0"},
                {"FINAL", "Cuối kỳ", "1"},
                {"QUIZ", "Bài kiểm tra", "2"},
                {"PRACTICE", "Luyện tập", "3"}
        };
        for (String[] p : purposes) {
            if (!examPurposeRepository.existsBySchoolIdAndCodeIgnoreCase(schoolId, p[0])) {
                ExamPurpose purpose = new ExamPurpose(schoolId, p[0], p[1]);
                purpose.setPosition(Integer.parseInt(p[2]));
                examPurposeRepository.saveAndFlush(purpose);
            }
        }
    }

    /**
     * Idempotently ensures: 1 demo TEACHER (+ profile), 3 demo STUDENTS (+
     * profiles), and one demo classroom owned by the teacher with the 3 students
     * as members — so the class-based session-visibility flow is exercisable
     * end-to-end (create classroom → add members → create CLASS_RESTRICTED
     * session → assign class → students see + start).
     */
    private void seedDemoClassroom(Long schoolId) {
        // Demo teacher (user + TEACHER role + TeacherProfile).
        TeacherProfile teacherProfile = teacherProfileRepository
                .findByUserId(ensureDemoUser(DEMO_TEACHER_USERNAME, schoolId, TEACHER_ROLE_CODE, "Demo Teacher"))
                .orElseThrow(() -> new IllegalStateException("demo teacher profile not created"));
        Long teacherProfileId = teacherProfile.getId();

        // Demo classroom (owner = teacher) — find-or-create.
        Classroom classroom = classroomRepository
                .findByOwnerTeacherIdAndCodeIgnoreCase(teacherProfileId, DEMO_CLASSROOM_CODE)
                .orElseGet(() -> classroomRepository.saveAndFlush(
                        new Classroom(schoolId, teacherProfileId, DEMO_CLASSROOM_CODE, "Demo Classroom")));

        // Demo students + memberships.
        for (String username : DEMO_STUDENT_USERNAMES) {
            Long studentUserId = ensureDemoUser(username, schoolId, STUDENT_ROLE_CODE,
                    username.replace('-', ' ').replace("student", "Student"));
            StudentProfile studentProfile = studentProfileRepository.findByUserId(studentUserId)
                    .orElseThrow(() -> new IllegalStateException("demo student profile not created: " + username));
            if (!classroomMemberRepository.existsByClassroomIdAndStudentProfileId(
                    classroom.getId(), studentProfile.getId())) {
                classroomMemberRepository.saveAndFlush(
                        new ClassroomMember(classroom.getId(), studentProfile.getId(), schoolId));
            }
        }
    }

    /** Find-or-create a demo user + role assignment + (academic) profile stub. Returns userId. */
    private Long ensureDemoUser(String username, Long schoolId, String roleCode, String displayName) {
        User user = userRepository.findByUsernameIgnoreCase(username).orElseGet(() -> {
            String hash = passwordHasher.hash(DEMO_PASSWORD);
            User u = new User(username, username + "@demo.quizopia.local", hash, displayName);
            return userRepository.saveAndFlush(u);
        });
        // Role assignment (idempotent via find — user_roles PK is (user_id, role_id)).
        if (userRoleRepository.findActiveRoleCodesByUserId(user.getId(), java.time.Instant.now()).isEmpty()) {
            Role role = roleRepository.findByCode(roleCode).orElseThrow(() -> new IllegalStateException(
                    "Required role '" + roleCode + "' is not seeded; check Flyway V3"));
            userRoleRepository.saveAndFlush(new UserRole(user, role, null, null));
        }
        // Academic profile (idempotent find-or-create).
        if (TEACHER_ROLE_CODE.equals(roleCode)) {
            teacherProfileRepository.findByUserId(user.getId())
                    .orElseGet(() -> teacherProfileRepository.saveAndFlush(
                            new TeacherProfile(user.getId(), schoolId, username)));
        } else {
            studentProfileRepository.findByUserId(user.getId())
                    .orElseGet(() -> studentProfileRepository.saveAndFlush(
                            new StudentProfile(user.getId(), schoolId, username)));
        }
        return user.getId();
    }
}
