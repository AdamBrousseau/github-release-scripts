package net.adoptopenjdk.release

import groovy.cli.picocli.CliBuilder
import groovy.cli.picocli.OptionAccessor
import groovy.transform.CompileStatic
import org.kohsuke.github.*
import org.kohsuke.github.extras.ImpatientHttpConnector

import java.nio.file.Files
import java.util.concurrent.TimeUnit

@CompileStatic
class UploadAdoptReleaseFiles {

    private final String tag
    private final String description
    private final boolean release
    private final List<File> files
    private final String version

    UploadAdoptReleaseFiles(String tag, String description, boolean release, String version, List<File> files) {
        this.tag = tag
        this.description = description
        this.release = release
        this.files = files
        this.version = version
    }

    void release() {
        def grouped = files.groupBy {
            switch (it.getName()) {
                case ~/.*dragonwell.*/: "dragonwell"; break;
                default: "adopt"; break
            }
        }
        grouped.each {entry ->
            GHRepository repo = getRepo(entry.getKey())
            println repo
            GHRelease release = getRelease(repo)
            println release
            println "entry:${entry}"
            println "entryValue:${entry.getValue()}"
            uploadFiles(release, entry.getValue())
        }
    }

    private GHRepository getRepo(String vendor) {
        String token = System.getenv("GITHUB_TOKEN")
        String org = System.getenv("GITHUB_ORG")
        String server = System.getenv("GITHUB_SERVER")
        if (token == null) {
            System.err.println("Could not find GITHUB_TOKEN")
            System.exit(1)
        }
        if (org == null) {
            System.err.println("Could not find GITHUB_ORG")
            System.exit(1)
        }
        if (server == null) {
            System.err.println("Could not find GITHUB_SERVER")
            System.exit(1)
        }

        GitHub github = GitHub.connectUsingOAuth(server, token)

        github
                .setConnector(new ImpatientHttpConnector(new HttpConnector() {
                    HttpURLConnection connect(URL url) throws IOException {
                        return (HttpURLConnection) url.openConnection()
                    }
                },
                        (int) TimeUnit.SECONDS.toMillis(120),
                        (int) TimeUnit.SECONDS.toMillis(120)))

        def repoName = "${org}/open${version}-binaries"

        if (vendor != "adopt") {
            repoName = "${org}/open${version}-${vendor}-binaries"
        }

        return github.getRepository(repoName)
    }

    private void uploadFiles(GHRelease release, List<File> files) {
        List<GHAsset> assets = release.getAssets()
        println "Assets:${assets}"
        files.each { file ->
            // Delete existing asset
            assets
                    .find({ it.name == file.name })
                    .each { GHAsset existing ->
                        println("Updating ${existing.name}")
                        existing.delete()
                    }

            println "file:${file}"
            println("Uploading ${file.name}")
            println "probeContentType:${Files.probeContentType(file.toPath())}"
            println "releaseUploadUrl:${release.getUploadUrl()}"
            release.uploadAsset(file, Files.probeContentType(file.toPath()))
        }
    }

    private GHRelease getRelease(GHRepository repo) {
        GHRelease release = repo
                .getReleaseByTagName(tag)

        if (release == null) {
            release = repo
                    .createRelease(tag)
                    .body(description)
                    .name(tag)
                    .prerelease(!this.release)
                    .create()
        }
        return release
    }
}


static void main(String[] args) {
    OptionAccessor options = parseArgs(args)

    List<File> files = options.arguments()
            .collect { new File(it) }

    new UploadAdoptReleaseFiles(
            options.t,
            options.d,
            options.r,
            options.v,
            files,
    ).release()
}

private OptionAccessor parseArgs(String[] args) {

    CliBuilder cliBuilder = new CliBuilder()

    cliBuilder
            .with {
                v longOpt: 'version', type: String, args: 1, 'JDK version'
                t longOpt: 'tag', type: String, args: 1, 'Tag name'
                d longOpt: 'description', type: String, args: 1, 'Release description'
                r longOpt: 'release', 'Is a release build'
                h longOpt: 'help', 'Show usage information'
            }

    def options = cliBuilder.parse(args)
    if (options.v && options.t && options.d) {
        return options
    }
    cliBuilder.usage()
    System.exit(1)
    return null
}
