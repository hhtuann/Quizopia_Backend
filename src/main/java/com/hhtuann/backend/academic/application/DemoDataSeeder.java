package com.hhtuann.backend.academic.application;

import com.hhtuann.backend.academic.domain.model.GradeLevel;
import com.hhtuann.backend.academic.domain.model.School;
import com.hhtuann.backend.academic.domain.model.Subject;
import com.hhtuann.backend.academic.repository.GradeLevelRepository;
import com.hhtuann.backend.academic.repository.SchoolRepository;
import com.hhtuann.backend.academic.repository.SubjectRepository;
import com.hhtuann.backend.exam.domain.model.ExamPurpose;
import com.hhtuann.backend.exam.repository.ExamPurposeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal, idempotent seeder that provisions a single demo school, grade
 * level and subject so the end-to-end MVP flow can be exercised without a
 * production onboarding step.
 *
 * <p>The seeder runs only when {@code quizopia.demo.data.enabled=true} (bound
 * from {@code QUIZOPIA_DEMO_DATA_ENABLED}, default {@code false}). It never
 * runs in production unless the flag is explicitly enabled. It contains no
 * secrets (no users, passwords, invite codes, JWT keys or AES keys) and exposes
 * no public endpoint.
 *
 * <p>Idempotency relies on case-insensitive unique business codes plus a
 * transactional {@code find-or-create} per row, so concurrent or repeated
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

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final SchoolRepository schoolRepository;
    private final GradeLevelRepository gradeLevelRepository;
    private final SubjectRepository subjectRepository;
    private final ExamPurposeRepository examPurposeRepository;
    private final boolean demoEnabled;

    public DemoDataSeeder(SchoolRepository schoolRepository,
                          GradeLevelRepository gradeLevelRepository,
                          SubjectRepository subjectRepository,
                          ExamPurposeRepository examPurposeRepository,
                          @Value("${quizopia.demo.data.enabled:false}") boolean demoEnabled) {
        this.schoolRepository = schoolRepository;
        this.gradeLevelRepository = gradeLevelRepository;
        this.subjectRepository = subjectRepository;
        this.examPurposeRepository = examPurposeRepository;
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

        GradeLevel gradeLevel = gradeLevelRepository
                .findBySchoolIdAndCodeIgnoreCase(schoolId, DEMO_GRADE_LEVEL_CODE)
                .orElseGet(() -> gradeLevelRepository.saveAndFlush(
                        new GradeLevel(schoolId, DEMO_GRADE_LEVEL_CODE, "Grade 12")));
        Long gradeLevelId = gradeLevel.getId();

        subjectRepository.findBySchoolIdAndGradeLevelIdAndCodeIgnoreCase(
                        schoolId, gradeLevelId, DEMO_SUBJECT_CODE)
                .orElseGet(() -> subjectRepository.saveAndFlush(
                        new Subject(schoolId, gradeLevelId, DEMO_SUBJECT_CODE, "General Mathematics")));

        seedExamPurposes(schoolId);

        log.info("Demo academic data ensured (school={}, gradeLevel={}, subject={})",
                DEMO_SCHOOL_CODE, DEMO_GRADE_LEVEL_CODE, DEMO_SUBJECT_CODE);
    }

    /**
     * Idempotently ensures the 4 default exam purposes for the demo school.
     * Uses case-insensitive code check to prevent duplicates.
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
}
