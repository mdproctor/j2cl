load("//build_def:j2cl_library.bzl", "J2CLProvider", "get_js_srcs")

def _impl(ctx):
  if not ctx.attr.deps:
      fail("closure_js_binary rules can not have an empty 'deps' list")

  js_srcs = get_js_srcs(None, None, ctx.attr.deps)




  ctx.action(
      outputs=[ctx.outputs.bundle_js_file],
      command = "echo \"bundle\" > %s " % ctx.outputs.bundle_js_file.path,
  )

  ctx.action(
        outputs=[ctx.outputs.optimized_js_file],
        command = "echo \"optmzd\" > %s " % ctx.outputs.optimized_js_file.path,
    )

  return struct(
      files=set([ctx.outputs.bundle_js_file, ctx.outputs.optimized_js_file])
  )

j2cl_binary = rule(
    attrs={
        "deps": attr.label_list(),
        "entry_points": attr.string_list(),
        "externs": attr.label_list(),
    },

    implementation = _impl,

    outputs={
        "bundle_js_file": "%{name}-bundle.js",
        "optimized_js_file": "%{name}.js"
    }
)
