load("//build_def:j2cl_transpile.bzl", "j2cl_transpile")

J2CLProvider = provider()

def get_js_srcs(zip_file, js_files, deps):
  trans_js_srcs = depset()
  for dep in deps:
    trans_srcs += dep[J2CLProvider].transitive_js_srcs

  if js_files:
    trans_js_srcs += js_files

  if zip_file:
    trans_js_srcs += zip_file

  return trans_js_srcs


def _impl(ctx):
  # Run javac
  # Run J2CL Transpile
  separator = ctx.configuration.host_path_separator
  output_jar = ctx.actions.declare_file(ctx.label.name + "-j2cl.jar")
  print(ctx.outputs.zip_file)
  java_common.compile(
      ctx,
      source_files = ctx.files.srcs,
      output = output_jar,
      java_toolchain = ctx.attr._java_toolchain,
      host_javabase = ctx.attr._host_javabase
  )

  zip_file_name = ctx.label.name + ".js.zip"
  zip_file = ctx.new_file(zip_file_name)

  dep_files = set()
  deps_paths = []

  # gather transitive files and exported files in deps
  for dep in ctx.attr.deps:
    dep_files += dep.files
    dep_files += dep.default_runfiles.files  # for exported libraries

  # convert files to paths
  for dep_file in dep_files:
    deps_paths += [dep_file.path]

  java_files_paths = []
  for java_file in ctx.files.srcs:
      java_files_paths += [java_file.path]

  arguments = []
  if deps_paths:
    arguments += ["-cp " + separator.join(deps_paths)]

  arguments += [
      "-d" ,ctx.configuration.bin_dir.path + "/" + ctx.label.package + "/" + zip_file_name,
  ] + java_files_paths

  print(arguments)

  ctx.action(
        progress_message = "pooping in open source",
        inputs= ctx.files.srcs + list(dep_files),
        outputs=[zip_file],
        executable=ctx.executable.transpiler,
        arguments= arguments,
        env=dict(LANG="en_US.UTF-8"),
        mnemonic = "J2clTranspile",
  )

  return struct(
#      zip_files = set([zip_file]),
#      js_files = set([zip_file]),
      jar = set([output_jar]),
      provider = J2CLProvider(transitive_js_srcs = get_js_srcs([zip_file], [], ctx.attr.deps))
  )


"""j2cl_transpile: A J2CL transpile rule.

Args:
  srcs: Source files (.java or .srcjar) to compile.
  deps: Java jar files for reference resolution.
  native_srcs_zips: JS zip files providing Foo.native.js implementations.
"""
# Private Args:
#   transpiler: J2CL compiler jar to use.
j2cl_library_open = rule(
    attrs={
        "deps": attr.label_list(allow_files=[".jar"]),
        "srcs": attr.label_list(
            mandatory=True,
            allow_files=[".java"],
        ),
        "native_srcs_zips": attr.label_list(
            allow_files=[".zip"],
        ),
        "js_srcs": attr.label_list(
            allow_files=[".js"],
        ),
        "_java_toolchain": attr.label(
            default = Label("@bazel_tools//tools/jdk:toolchain")
        ),
        "_host_javabase": attr.label(
            default = Label("@local_jdk//:jdk-default")
        ),
        "transpiler": attr.label(
            cfg="host",
            executable=True,
            allow_files=True,
            default=Label("//internal_do_not_use:J2clTranspiler"),
        ),
    },
    fragments = ["java"],
    implementation = _impl,
    outputs={
        "zip_file": "%{name}.js.zip",
    }
)
