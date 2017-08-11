"""j2cl_import build macro

Takes nonstandard input and repackages it with names that will allow the
j2cl_import() target to be directly depended upon from j2cl_library() targets.
The nonstandard input can be a source jar, a jar, or both a jar and js.


Example use:

# creates properly named forwarding rules
#     java_library(name="Qux_java_library")
#     js_library(name="Qux")
j2cl_import(
  name = "Qux",
  jar = "//java/com/qux:qux_java",  # nonconforming name
  js = "//java/com/qux:qux_js-lib",  # nonconforming name
)

# creates js_library(name="Bar") containing the results.
j2cl_library(
    name = "Bar",
    srcs = glob(["Bar.java"]),
    deps = [":Qux"],  # the j2cl_import target
)

"""
def _impl(ctx):
  return struct(
      jar = ctx.files.jar,
  )

j2cl_import = rule(
  implementation = _impl,
  attrs = {
    "jar": attr.label_list(allow_files=[".jar"]),
  },
)
