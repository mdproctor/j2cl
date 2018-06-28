package(
    licenses = ["reciprocal"],  # Eclipse Public License - v 1.0
)

load("//build_defs:rules.bzl", "j2cl_library")

j2cl_library(
    name = "junit_emul",
    srcs = glob(["**/*.java"]),
    visibility = [
        "//jre:__subpackages__",
        "//:__pkg__"
    ],
)
