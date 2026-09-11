package com.anuragbhandary.jobradar.apply.resume.rewrite;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import java.util.List;
import java.util.Map;

/**
 * Builds the candidate resume from the deterministic one and a set of accepted
 * bullet rewrites. Shadow only: nothing in the application flow calls this.
 *
 * <p>Every structural decision stays the deterministic tailor's - which jobs,
 * which projects, which bullets, in what order, and which approved summary. Only
 * the wording of bullets whose rewrite passed validation changes, matched by
 * source id. The summary is always the deterministic one: there is no parameter
 * that could replace it. Employer, title, dates, project names, education and
 * contact details are copied untouched, because no rewrite was ever given them.
 */
public final class GenerativeTailor {

    private GenerativeTailor() {
    }

    /**
     * @param accepted source id to accepted rewrite; anything absent keeps its
     *                 source wording
     * @param skills   the skills in their new order, same items
     */
    public static TailoredResume assemble(TailoredResume base, ResumeSources sources,
            Map<String, String> accepted, List<ResumeModel.SkillGroup> skills) {

        List<ResumeModel.Job> jobs = base.experience().stream().map(job -> new ResumeModel.Job(
                job.company(), job.title(), job.location(), job.period(), job.note(),
                rewrite(job.bullets(), sources, accepted))).toList();

        List<ResumeModel.Project> projects = base.projects().stream().map(project ->
                new ResumeModel.Project(project.name(), project.stack(), project.tags(),
                        rewrite(project.bullets(), sources, accepted))).toList();

        return new TailoredResume(base.summary(), skills, jobs, projects, base.education(),
                base.extras(), base.matchedTags(), base.droppedProjects());
    }

    private static List<ResumeModel.Bullet> rewrite(List<ResumeModel.Bullet> bullets,
            ResumeSources sources, Map<String, String> accepted) {
        if (bullets == null) {
            return List.of();
        }
        return bullets.stream().map(bullet -> sources.idOf(bullet)
                .map(accepted::get)
                .map(text -> new ResumeModel.Bullet(text, bullet.tags(), bullet.id()))
                .orElse(bullet)).toList();
    }
}
