package org.eclipse.buildship.core.launch

class RunGradleTestLaunchRequestJobTest extends BaseLaunchRequestJobTest {

    def "Job launches a Gradle test"() {
        setup:
        def job = new RunGradleTestLaunchRequestJob(createTestOperationDescriptorsMock(), createRunConfigurationAttribuetesMock())

        when:
        job.schedule()
        job.join()

        then:
        job.getResult().isOK()
        1 * testRequest.executeAndWait()
    }

    def "Job prints its configuration"() {
        setup:
        def job = new RunGradleTestLaunchRequestJob(createTestOperationDescriptorsMock(), createRunConfigurationAttribuetesMock())

        when:
        job.schedule()
        job.join()

        then:
        job.getResult().isOK()
        1 * processStreamsProvider.createProcessStreams(null).getConfiguration().flush()
    }
}