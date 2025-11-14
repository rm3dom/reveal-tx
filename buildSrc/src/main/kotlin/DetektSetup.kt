import dev.detekt.gradle.Detekt
import org.gradle.api.Project
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.withType


fun Project.setupDetekt() {
    tasks.withType<Detekt>().configureEach {
        enabled = true
        allRules = true
        buildUponDefaultConfig = true
        autoCorrect = true
        config.setFrom("${rootProject.projectDir}/detekt.yml")
        source = project.fileTree("src").matching {
            include("**/*.kt")
        }
        exclude("*Test", "**/*Test*")
    }
}
