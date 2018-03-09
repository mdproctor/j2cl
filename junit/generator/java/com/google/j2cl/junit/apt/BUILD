# Apache2
licenses(["notice"])

java_plugin(
    name = "junit_processor_plugin",
    testonly = 1,
    processor_class = "com.google.j2cl.junit.apt.J2clTestingProcessor",
    deps = [":junit_processor"],
)

java_library(
    name = "junit_processor",
    testonly = 1,
    srcs = glob(["*.java"]),
    resources = glob(["*.vm"]),
    visibility = ["//junit/generator/javatests/com/google/j2cl/junit/apt:__pkg__"],
    deps = [
        "//third_party:auto_common",
        "//third_party:auto_service",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:gwt-jsinterop-annotations",
        "//third_party:jakarta_velocity",
        "//third_party:jsr305_annotations",
        "//third_party:junit",
    ],
)