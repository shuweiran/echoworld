import{S as r}from"./BabylonSimulationView-CdKNwLC_.js";import"./index-M38jcyXX.js";const e="textureAlphaCheckerVertexShader",t=`
attribute uv: vec2f;varying vUv: vec2f;@vertex
fn main(input: VertexInputs)->FragmentInputs {vertexOutputs.vUv=vertexInputs.uv;vertexOutputs.position=vec4f(
(vertexInputs.uv % 1.0)*2.0-1.0,
0.0,
1.0
);}
`;r.ShadersStoreWGSL[e]=t;const a={name:e,shader:t};export{a as TextureAlphaCheckerVertexShader};
