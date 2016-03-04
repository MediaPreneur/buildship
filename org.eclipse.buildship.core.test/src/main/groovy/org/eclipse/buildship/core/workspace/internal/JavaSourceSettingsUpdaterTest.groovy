package org.eclipse.buildship.core.workspace.internal

import com.gradleware.tooling.toolingmodel.OmniJavaRuntime
import com.gradleware.tooling.toolingmodel.OmniJavaSourceSettings
import com.gradleware.tooling.toolingmodel.OmniJavaVersion
import org.eclipse.buildship.core.CorePlugin
import org.eclipse.buildship.core.GradlePluginsRuntimeException
import org.eclipse.buildship.core.test.fixtures.EclipseProjects
import org.eclipse.buildship.core.test.fixtures.LegacyEclipseSpockTestHelper
import org.eclipse.buildship.core.workspace.internal.JavaSourceSettingsUpdaterTest.BuildJobScheduledByJavaSourceSettingsUpdater

import org.eclipse.core.internal.resources.Workspace
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IResource
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.runtime.Path
import org.eclipse.core.runtime.jobs.IJobChangeEvent
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.core.runtime.jobs.JobChangeAdapter
import org.eclipse.jdt.core.IClasspathEntry
import org.eclipse.jdt.core.IJavaProject
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.launching.JavaRuntime
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

@SuppressWarnings("restriction")
class JavaSourceSettingsUpdaterTest extends Specification {

    @Rule
    TemporaryFolder tempFolder

    def cleanup() {
        CorePlugin.workspaceOperations().deleteAllProjects(new NullProgressMonitor())
    }

    def "Can set valid source settings"() {
        given:
        IJavaProject project = EclipseProjects.newJavaProject('sample-project', tempFolder.newFolder())

        when:
        JavaSourceSettingsUpdater.update(project, sourceSettings(sourceVersion, targetVersion), new NullProgressMonitor())

        then:
        project.getOption(JavaCore.COMPILER_COMPLIANCE, true) == sourceVersion
        project.getOption(JavaCore.COMPILER_SOURCE, true) == sourceVersion
        project.getOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, true) == targetVersion

        where:
        sourceVersion | targetVersion
        '1.2'         | '1.2'
        '1.4'         | '1.5'
    }

    @SuppressWarnings("GroovyAccessibility")
    def "Invalid source setting results in runtime exception"() {
        given:
        IJavaProject project = EclipseProjects.newJavaProject('sample-project', tempFolder.newFolder())

        when:
        JavaSourceSettingsUpdater.update(project, sourceSettings(version, '1.3'), new NullProgressMonitor())

        then:
        thrown GradlePluginsRuntimeException

        when:
        JavaSourceSettingsUpdater.update(project, sourceSettings('1.4', version), new NullProgressMonitor())

        then:
        thrown GradlePluginsRuntimeException

        where:
        version << [null, '', '1.0.0', '7.8', 'string']
    }

    def "VM added to the project classpath if not exist"() {
        given:
        IJavaProject project = EclipseProjects.newJavaProject('sample-project', tempFolder.newFolder())
        def classpathWithoutVM = project.rawClasspath.findAll { !it.path.segment(0).equals(JavaRuntime.JRE_CONTAINER) }
        project.setRawClasspath(classpathWithoutVM as IClasspathEntry[], null)

        expect:
        !project.rawClasspath.find { it.path.segment(0).equals(JavaRuntime.JRE_CONTAINER) }

        when:
        JavaSourceSettingsUpdater.update(project, sourceSettings('1.6', '1.6'), new NullProgressMonitor())

        then:
        project.rawClasspath.find { it.path.segment(0).equals(JavaRuntime.JRE_CONTAINER) }
    }

    def "Existing VM on the project classpath updated"() {
        given:
        IJavaProject project = EclipseProjects.newJavaProject('sample-project', tempFolder.newFolder())
        def updatedClasspath = project.rawClasspath.findAll { !it.path.segment(0).equals(JavaRuntime.JRE_CONTAINER) }
        updatedClasspath += JavaCore.newContainerEntry(new Path('org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/custom'))
        project.setRawClasspath(updatedClasspath as IClasspathEntry[], null)

        expect:
        project.rawClasspath.find {
            it.path.toPortableString().equals('org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/custom')
        }

        when:
        JavaSourceSettingsUpdater.update(project, sourceSettings('1.6', '1.6'), new NullProgressMonitor())

        then:
        project.rawClasspath.find {
            it.path.toPortableString().startsWith('org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType')
        }
        !project.rawClasspath.find {
            it.path.toPortableString().equals('org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/custom')
        }
    }

    def "A project is rebuilt if the source settings have changed"() {
        setup:
        IJavaProject javaProject = EclipseProjects.newJavaProject('sample-project', tempFolder.root)
        def buildScheduledListener = new BuildJobScheduledByJavaSourceSettingsUpdater()
        Job.jobManager.addJobChangeListener(buildScheduledListener)

        when:
        JavaSourceSettingsUpdater.update(javaProject, sourceSettings('1.4', '1.4'), new NullProgressMonitor())

        then:
        buildScheduledListener.isBuildScheduled()

        cleanup:
        Job.jobManager.removeJobChangeListener(buildScheduledListener)
    }

    def "A project is not rebuilt if source settings have not changed"() {
        setup:
        IJavaProject javaProject = EclipseProjects.newJavaProject('sample-project', tempFolder.root)

        when:
        JavaSourceSettingsUpdater.update(javaProject, sourceSettings('1.4', '1.4'), new NullProgressMonitor())
        def buildScheduledListener = new BuildJobScheduledByJavaSourceSettingsUpdater()
        Job.jobManager.addJobChangeListener(buildScheduledListener)
        JavaSourceSettingsUpdater.update(javaProject, sourceSettings('1.4', '1.4'), new NullProgressMonitor())

        then:
        !buildScheduledListener.isBuildScheduled()

        cleanup:
        Job.jobManager.removeJobChangeListener(buildScheduledListener)
    }

    def "A project is not rebuilt if the 'Build Automatically' setting is disabled"() {
        setup:
        IJavaProject javaProject = EclipseProjects.newJavaProject('sample-project', tempFolder.root)
        def buildScheduledListener = new BuildJobScheduledByJavaSourceSettingsUpdater()
        Job.jobManager.addJobChangeListener(buildScheduledListener)
        def description = LegacyEclipseSpockTestHelper.workspace.description
        def wasAutoBuilding = description.autoBuilding
        description.autoBuilding = false
        LegacyEclipseSpockTestHelper.workspace.description = description

        when:
        JavaSourceSettingsUpdater.update(javaProject, sourceSettings('1.4', '1.4'), new NullProgressMonitor())

        then:
        !buildScheduledListener.isBuildScheduled()

        cleanup:
        description.autoBuilding = wasAutoBuilding
        LegacyEclipseSpockTestHelper.workspace.description = description
        Job.jobManager.removeJobChangeListener(buildScheduledListener)
    }

    private OmniJavaSourceSettings sourceSettings(String sourceVersion, String targetVersion) {
        OmniJavaRuntime rt = Mock(OmniJavaRuntime)
        rt.homeDirectory >> new File(System.getProperty('java.home'))
        rt.javaVersion >> Mock(OmniJavaVersion)

        OmniJavaVersion target = Mock(OmniJavaVersion)
        target.name >> targetVersion

        OmniJavaVersion source = Mock(OmniJavaVersion)
        source.name >> sourceVersion

        OmniJavaSourceSettings settings = Mock(OmniJavaSourceSettings)
        settings.targetRuntime >> rt
        settings.targetBytecodeLevel >> target
        settings.sourceLanguageLevel >> source

        settings
    }

    static class BuildJobScheduledByJavaSourceSettingsUpdater extends JobChangeAdapter {

        boolean buildScheduled = false

        @Override
        public void scheduled(IJobChangeEvent event) {
            if (event.job.class.name.startsWith(JavaSourceSettingsUpdater.class.name)) {
                buildScheduled = true
            }
        }

        boolean isRebuildScheduled() {
            return buildScheduled
        }
    }

}
