//! Macros allowing generation of swizzling getters and setters.
//! See the docs of [`build.rs`] and usage places to learn more.



// !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
// THIS IS AN AUTO-GENERATED FILE. DO NOT EDIT IT DIRECTLY!
// !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!


/// Swizzling data for the given dimension.
/// See the [`build.rs`] file to learn more.
#[macro_export]
macro_rules! with_swizzling_for_dim {
    (1, $f: ident $(,$($args:tt)*)?) => { $f! { $([$($args)*])? 1
        x 1 [0] [0]
    }};
    (2, $f: ident $(,$($args:tt)*)?) => { $f! { $([$($args)*])? 2
        y 1 [1] [0]
        xx 2 [0, 0] [0, 1]
        xy 2 [0, 1] [0, 1]
        yx 2 [1, 0] [0, 1]
        yy 2 [1, 1] [0, 1]
    }};
    (3, $f: ident $(,$($args:tt)*)?) => { $f! { $([$($args)*])? 3
        z 1 [2] [0]
        xz 2 [0, 2] [0, 1]
        yz 2 [1, 2] [0, 1]
        zx 2 [2, 0] [0, 1]
        zy 2 [2, 1] [0, 1]
        zz 2 [2, 2] [0, 1]
        xxx 3 [0, 0, 0] [0, 1, 2]
        xxy 3 [0, 0, 1] [0, 1, 2]
        xxz 3 [0, 0, 2] [0, 1, 2]
        xyx 3 [0, 1, 0] [0, 1, 2]
        xyy 3 [0, 1, 1] [0, 1, 2]
        xyz 3 [0, 1, 2] [0, 1, 2]
        xzx 3 [0, 2, 0] [0, 1, 2]
        xzy 3 [0, 2, 1] [0, 1, 2]
        xzz 3 [0, 2, 2] [0, 1, 2]
        yxx 3 [1, 0, 0] [0, 1, 2]
        yxy 3 [1, 0, 1] [0, 1, 2]
        yxz 3 [1, 0, 2] [0, 1, 2]
        yyx 3 [1, 1, 0] [0, 1, 2]
        yyy 3 [1, 1, 1] [0, 1, 2]
        yyz 3 [1, 1, 2] [0, 1, 2]
        yzx 3 [1, 2, 0] [0, 1, 2]
        yzy 3 [1, 2, 1] [0, 1, 2]
        yzz 3 [1, 2, 2] [0, 1, 2]
        zxx 3 [2, 0, 0] [0, 1, 2]
        zxy 3 [2, 0, 1] [0, 1, 2]
        zxz 3 [2, 0, 2] [0, 1, 2]
        zyx 3 [2, 1, 0] [0, 1, 2]
        zyy 3 [2, 1, 1] [0, 1, 2]
        zyz 3 [2, 1, 2] [0, 1, 2]
        zzx 3 [2, 2, 0] [0, 1, 2]
        zzy 3 [2, 2, 1] [0, 1, 2]
        zzz 3 [2, 2, 2] [0, 1, 2]
    }};
    (4, $f: ident $(,$($args:tt)*)?) => { $f! { $([$($args)*])? 4
        w 1 [3] [0]
        xw 2 [0, 3] [0, 1]
        yw 2 [1, 3] [0, 1]
        zw 2 [2, 3] [0, 1]
        wx 2 [3, 0] [0, 1]
        wy 2 [3, 1] [0, 1]
        wz 2 [3, 2] [0, 1]
        ww 2 [3, 3] [0, 1]
        xxw 3 [0, 0, 3] [0, 1, 2]
        xyw 3 [0, 1, 3] [0, 1, 2]
        xzw 3 [0, 2, 3] [0, 1, 2]
        xwx 3 [0, 3, 0] [0, 1, 2]
        xwy 3 [0, 3, 1] [0, 1, 2]
        xwz 3 [0, 3, 2] [0, 1, 2]
        xww 3 [0, 3, 3] [0, 1, 2]
        yxw 3 [1, 0, 3] [0, 1, 2]
        yyw 3 [1, 1, 3] [0, 1, 2]
        yzw 3 [1, 2, 3] [0, 1, 2]
        ywx 3 [1, 3, 0] [0, 1, 2]
        ywy 3 [1, 3, 1] [0, 1, 2]
        ywz 3 [1, 3, 2] [0, 1, 2]
        yww 3 [1, 3, 3] [0, 1, 2]
        zxw 3 [2, 0, 3] [0, 1, 2]
        zyw 3 [2, 1, 3] [0, 1, 2]
        zzw 3 [2, 2, 3] [0, 1, 2]
        zwx 3 [2, 3, 0] [0, 1, 2]
        zwy 3 [2, 3, 1] [0, 1, 2]
        zwz 3 [2, 3, 2] [0, 1, 2]
        zww 3 [2, 3, 3] [0, 1, 2]
        wxx 3 [3, 0, 0] [0, 1, 2]
        wxy 3 [3, 0, 1] [0, 1, 2]
        wxz 3 [3, 0, 2] [0, 1, 2]
        wxw 3 [3, 0, 3] [0, 1, 2]
        wyx 3 [3, 1, 0] [0, 1, 2]
        wyy 3 [3, 1, 1] [0, 1, 2]
        wyz 3 [3, 1, 2] [0, 1, 2]
        wyw 3 [3, 1, 3] [0, 1, 2]
        wzx 3 [3, 2, 0] [0, 1, 2]
        wzy 3 [3, 2, 1] [0, 1, 2]
        wzz 3 [3, 2, 2] [0, 1, 2]
        wzw 3 [3, 2, 3] [0, 1, 2]
        wwx 3 [3, 3, 0] [0, 1, 2]
        wwy 3 [3, 3, 1] [0, 1, 2]
        wwz 3 [3, 3, 2] [0, 1, 2]
        www 3 [3, 3, 3] [0, 1, 2]
        xxxx 4 [0, 0, 0, 0] [0, 1, 2, 3]
        xxxy 4 [0, 0, 0, 1] [0, 1, 2, 3]
        xxxz 4 [0, 0, 0, 2] [0, 1, 2, 3]
        xxxw 4 [0, 0, 0, 3] [0, 1, 2, 3]
        xxyx 4 [0, 0, 1, 0] [0, 1, 2, 3]
        xxyy 4 [0, 0, 1, 1] [0, 1, 2, 3]
        xxyz 4 [0, 0, 1, 2] [0, 1, 2, 3]
        xxyw 4 [0, 0, 1, 3] [0, 1, 2, 3]
        xxzx 4 [0, 0, 2, 0] [0, 1, 2, 3]
        xxzy 4 [0, 0, 2, 1] [0, 1, 2, 3]
        xxzz 4 [0, 0, 2, 2] [0, 1, 2, 3]
        xxzw 4 [0, 0, 2, 3] [0, 1, 2, 3]
        xxwx 4 [0, 0, 3, 0] [0, 1, 2, 3]
        xxwy 4 [0, 0, 3, 1] [0, 1, 2, 3]
        xxwz 4 [0, 0, 3, 2] [0, 1, 2, 3]
        xxww 4 [0, 0, 3, 3] [0, 1, 2, 3]
        xyxx 4 [0, 1, 0, 0] [0, 1, 2, 3]
        xyxy 4 [0, 1, 0, 1] [0, 1, 2, 3]
        xyxz 4 [0, 1, 0, 2] [0, 1, 2, 3]
        xyxw 4 [0, 1, 0, 3] [0, 1, 2, 3]
        xyyx 4 [0, 1, 1, 0] [0, 1, 2, 3]
        xyyy 4 [0, 1, 1, 1] [0, 1, 2, 3]
        xyyz 4 [0, 1, 1, 2] [0, 1, 2, 3]
        xyyw 4 [0, 1, 1, 3] [0, 1, 2, 3]
        xyzx 4 [0, 1, 2, 0] [0, 1, 2, 3]
        xyzy 4 [0, 1, 2, 1] [0, 1, 2, 3]
        xyzz 4 [0, 1, 2, 2] [0, 1, 2, 3]
        xyzw 4 [0, 1, 2, 3] [0, 1, 2, 3]
        xywx 4 [0, 1, 3, 0] [0, 1, 2, 3]
        xywy 4 [0, 1, 3, 1] [0, 1, 2, 3]
        xywz 4 [0, 1, 3, 2] [0, 1, 2, 3]
        xyww 4 [0, 1, 3, 3] [0, 1, 2, 3]
        xzxx 4 [0, 2, 0, 0] [0, 1, 2, 3]
        xzxy 4 [0, 2, 0, 1] [0, 1, 2, 3]
        xzxz 4 [0, 2, 0, 2] [0, 1, 2, 3]
        xzxw 4 [0, 2, 0, 3] [0, 1, 2, 3]
        xzyx 4 [0, 2, 1, 0] [0, 1, 2, 3]
        xzyy 4 [0, 2, 1, 1] [0, 1, 2, 3]
        xzyz 4 [0, 2, 1, 2] [0, 1, 2, 3]
        xzyw 4 [0, 2, 1, 3] [0, 1, 2, 3]
        xzzx 4 [0, 2, 2, 0] [0, 1, 2, 3]
        xzzy 4 [0, 2, 2, 1] [0, 1, 2, 3]
        xzzz 4 [0, 2, 2, 2] [0, 1, 2, 3]
        xzzw 4 [0, 2, 2, 3] [0, 1, 2, 3]
        xzwx 4 [0, 2, 3, 0] [0, 1, 2, 3]
        xzwy 4 [0, 2, 3, 1] [0, 1, 2, 3]
        xzwz 4 [0, 2, 3, 2] [0, 1, 2, 3]
        xzww 4 [0, 2, 3, 3] [0, 1, 2, 3]
        xwxx 4 [0, 3, 0, 0] [0, 1, 2, 3]
        xwxy 4 [0, 3, 0, 1] [0, 1, 2, 3]
        xwxz 4 [0, 3, 0, 2] [0, 1, 2, 3]
        xwxw 4 [0, 3, 0, 3] [0, 1, 2, 3]
        xwyx 4 [0, 3, 1, 0] [0, 1, 2, 3]
        xwyy 4 [0, 3, 1, 1] [0, 1, 2, 3]
        xwyz 4 [0, 3, 1, 2] [0, 1, 2, 3]
        xwyw 4 [0, 3, 1, 3] [0, 1, 2, 3]
        xwzx 4 [0, 3, 2, 0] [0, 1, 2, 3]
        xwzy 4 [0, 3, 2, 1] [0, 1, 2, 3]
        xwzz 4 [0, 3, 2, 2] [0, 1, 2, 3]
        xwzw 4 [0, 3, 2, 3] [0, 1, 2, 3]
        xwwx 4 [0, 3, 3, 0] [0, 1, 2, 3]
        xwwy 4 [0, 3, 3, 1] [0, 1, 2, 3]
        xwwz 4 [0, 3, 3, 2] [0, 1, 2, 3]
        xwww 4 [0, 3, 3, 3] [0, 1, 2, 3]
        yxxx 4 [1, 0, 0, 0] [0, 1, 2, 3]
        yxxy 4 [1, 0, 0, 1] [0, 1, 2, 3]
        yxxz 4 [1, 0, 0, 2] [0, 1, 2, 3]
        yxxw 4 [1, 0, 0, 3] [0, 1, 2, 3]
        yxyx 4 [1, 0, 1, 0] [0, 1, 2, 3]
        yxyy 4 [1, 0, 1, 1] [0, 1, 2, 3]
        yxyz 4 [1, 0, 1, 2] [0, 1, 2, 3]
        yxyw 4 [1, 0, 1, 3] [0, 1, 2, 3]
        yxzx 4 [1, 0, 2, 0] [0, 1, 2, 3]
        yxzy 4 [1, 0, 2, 1] [0, 1, 2, 3]
        yxzz 4 [1, 0, 2, 2] [0, 1, 2, 3]
        yxzw 4 [1, 0, 2, 3] [0, 1, 2, 3]
        yxwx 4 [1, 0, 3, 0] [0, 1, 2, 3]
        yxwy 4 [1, 0, 3, 1] [0, 1, 2, 3]
        yxwz 4 [1, 0, 3, 2] [0, 1, 2, 3]
        yxww 4 [1, 0, 3, 3] [0, 1, 2, 3]
        yyxx 4 [1, 1, 0, 0] [0, 1, 2, 3]
        yyxy 4 [1, 1, 0, 1] [0, 1, 2, 3]
        yyxz 4 [1, 1, 0, 2] [0, 1, 2, 3]
        yyxw 4 [1, 1, 0, 3] [0, 1, 2, 3]
        yyyx 4 [1, 1, 1, 0] [0, 1, 2, 3]
        yyyy 4 [1, 1, 1, 1] [0, 1, 2, 3]
        yyyz 4 [1, 1, 1, 2] [0, 1, 2, 3]
        yyyw 4 [1, 1, 1, 3] [0, 1, 2, 3]
        yyzx 4 [1, 1, 2, 0] [0, 1, 2, 3]
        yyzy 4 [1, 1, 2, 1] [0, 1, 2, 3]
        yyzz 4 [1, 1, 2, 2] [0, 1, 2, 3]
        yyzw 4 [1, 1, 2, 3] [0, 1, 2, 3]
        yywx 4 [1, 1, 3, 0] [0, 1, 2, 3]
        yywy 4 [1, 1, 3, 1] [0, 1, 2, 3]
        yywz 4 [1, 1, 3, 2] [0, 1, 2, 3]
        yyww 4 [1, 1, 3, 3] [0, 1, 2, 3]
        yzxx 4 [1, 2, 0, 0] [0, 1, 2, 3]
        yzxy 4 [1, 2, 0, 1] [0, 1, 2, 3]
        yzxz 4 [1, 2, 0, 2] [0, 1, 2, 3]
        yzxw 4 [1, 2, 0, 3] [0, 1, 2, 3]
        yzyx 4 [1, 2, 1, 0] [0, 1, 2, 3]
        yzyy 4 [1, 2, 1, 1] [0, 1, 2, 3]
        yzyz 4 [1, 2, 1, 2] [0, 1, 2, 3]
        yzyw 4 [1, 2, 1, 3] [0, 1, 2, 3]
        yzzx 4 [1, 2, 2, 0] [0, 1, 2, 3]
        yzzy 4 [1, 2, 2, 1] [0, 1, 2, 3]
        yzzz 4 [1, 2, 2, 2] [0, 1, 2, 3]
        yzzw 4 [1, 2, 2, 3] [0, 1, 2, 3]
        yzwx 4 [1, 2, 3, 0] [0, 1, 2, 3]
        yzwy 4 [1, 2, 3, 1] [0, 1, 2, 3]
        yzwz 4 [1, 2, 3, 2] [0, 1, 2, 3]
        yzww 4 [1, 2, 3, 3] [0, 1, 2, 3]
        ywxx 4 [1, 3, 0, 0] [0, 1, 2, 3]
        ywxy 4 [1, 3, 0, 1] [0, 1, 2, 3]
        ywxz 4 [1, 3, 0, 2] [0, 1, 2, 3]
        ywxw 4 [1, 3, 0, 3] [0, 1, 2, 3]
        ywyx 4 [1, 3, 1, 0] [0, 1, 2, 3]
        ywyy 4 [1, 3, 1, 1] [0, 1, 2, 3]
        ywyz 4 [1, 3, 1, 2] [0, 1, 2, 3]
        ywyw 4 [1, 3, 1, 3] [0, 1, 2, 3]
        ywzx 4 [1, 3, 2, 0] [0, 1, 2, 3]
        ywzy 4 [1, 3, 2, 1] [0, 1, 2, 3]
        ywzz 4 [1, 3, 2, 2] [0, 1, 2, 3]
        ywzw 4 [1, 3, 2, 3] [0, 1, 2, 3]
        ywwx 4 [1, 3, 3, 0] [0, 1, 2, 3]
        ywwy 4 [1, 3, 3, 1] [0, 1, 2, 3]
        ywwz 4 [1, 3, 3, 2] [0, 1, 2, 3]
        ywww 4 [1, 3, 3, 3] [0, 1, 2, 3]
        zxxx 4 [2, 0, 0, 0] [0, 1, 2, 3]
        zxxy 4 [2, 0, 0, 1] [0, 1, 2, 3]
        zxxz 4 [2, 0, 0, 2] [0, 1, 2, 3]
        zxxw 4 [2, 0, 0, 3] [0, 1, 2, 3]
        zxyx 4 [2, 0, 1, 0] [0, 1, 2, 3]
        zxyy 4 [2, 0, 1, 1] [0, 1, 2, 3]
        zxyz 4 [2, 0, 1, 2] [0, 1, 2, 3]
        zxyw 4 [2, 0, 1, 3] [0, 1, 2, 3]
        zxzx 4 [2, 0, 2, 0] [0, 1, 2, 3]
        zxzy 4 [2, 0, 2, 1] [0, 1, 2, 3]
        zxzz 4 [2, 0, 2, 2] [0, 1, 2, 3]
        zxzw 4 [2, 0, 2, 3] [0, 1, 2, 3]
        zxwx 4 [2, 0, 3, 0] [0, 1, 2, 3]
        zxwy 4 [2, 0, 3, 1] [0, 1, 2, 3]
        zxwz 4 [2, 0, 3, 2] [0, 1, 2, 3]
        zxww 4 [2, 0, 3, 3] [0, 1, 2, 3]
        zyxx 4 [2, 1, 0, 0] [0, 1, 2, 3]
        zyxy 4 [2, 1, 0, 1] [0, 1, 2, 3]
        zyxz 4 [2, 1, 0, 2] [0, 1, 2, 3]
        zyxw 4 [2, 1, 0, 3] [0, 1, 2, 3]
        zyyx 4 [2, 1, 1, 0] [0, 1, 2, 3]
        zyyy 4 [2, 1, 1, 1] [0, 1, 2, 3]
        zyyz 4 [2, 1, 1, 2] [0, 1, 2, 3]
        zyyw 4 [2, 1, 1, 3] [0, 1, 2, 3]
        zyzx 4 [2, 1, 2, 0] [0, 1, 2, 3]
        zyzy 4 [2, 1, 2, 1] [0, 1, 2, 3]
        zyzz 4 [2, 1, 2, 2] [0, 1, 2, 3]
        zyzw 4 [2, 1, 2, 3] [0, 1, 2, 3]
        zywx 4 [2, 1, 3, 0] [0, 1, 2, 3]
        zywy 4 [2, 1, 3, 1] [0, 1, 2, 3]
        zywz 4 [2, 1, 3, 2] [0, 1, 2, 3]
        zyww 4 [2, 1, 3, 3] [0, 1, 2, 3]
        zzxx 4 [2, 2, 0, 0] [0, 1, 2, 3]
        zzxy 4 [2, 2, 0, 1] [0, 1, 2, 3]
        zzxz 4 [2, 2, 0, 2] [0, 1, 2, 3]
        zzxw 4 [2, 2, 0, 3] [0, 1, 2, 3]
        zzyx 4 [2, 2, 1, 0] [0, 1, 2, 3]
        zzyy 4 [2, 2, 1, 1] [0, 1, 2, 3]
        zzyz 4 [2, 2, 1, 2] [0, 1, 2, 3]
        zzyw 4 [2, 2, 1, 3] [0, 1, 2, 3]
        zzzx 4 [2, 2, 2, 0] [0, 1, 2, 3]
        zzzy 4 [2, 2, 2, 1] [0, 1, 2, 3]
        zzzz 4 [2, 2, 2, 2] [0, 1, 2, 3]
        zzzw 4 [2, 2, 2, 3] [0, 1, 2, 3]
        zzwx 4 [2, 2, 3, 0] [0, 1, 2, 3]
        zzwy 4 [2, 2, 3, 1] [0, 1, 2, 3]
        zzwz 4 [2, 2, 3, 2] [0, 1, 2, 3]
        zzww 4 [2, 2, 3, 3] [0, 1, 2, 3]
        zwxx 4 [2, 3, 0, 0] [0, 1, 2, 3]
        zwxy 4 [2, 3, 0, 1] [0, 1, 2, 3]
        zwxz 4 [2, 3, 0, 2] [0, 1, 2, 3]
        zwxw 4 [2, 3, 0, 3] [0, 1, 2, 3]
        zwyx 4 [2, 3, 1, 0] [0, 1, 2, 3]
        zwyy 4 [2, 3, 1, 1] [0, 1, 2, 3]
        zwyz 4 [2, 3, 1, 2] [0, 1, 2, 3]
        zwyw 4 [2, 3, 1, 3] [0, 1, 2, 3]
        zwzx 4 [2, 3, 2, 0] [0, 1, 2, 3]
        zwzy 4 [2, 3, 2, 1] [0, 1, 2, 3]
        zwzz 4 [2, 3, 2, 2] [0, 1, 2, 3]
        zwzw 4 [2, 3, 2, 3] [0, 1, 2, 3]
        zwwx 4 [2, 3, 3, 0] [0, 1, 2, 3]
        zwwy 4 [2, 3, 3, 1] [0, 1, 2, 3]
        zwwz 4 [2, 3, 3, 2] [0, 1, 2, 3]
        zwww 4 [2, 3, 3, 3] [0, 1, 2, 3]
        wxxx 4 [3, 0, 0, 0] [0, 1, 2, 3]
        wxxy 4 [3, 0, 0, 1] [0, 1, 2, 3]
        wxxz 4 [3, 0, 0, 2] [0, 1, 2, 3]
        wxxw 4 [3, 0, 0, 3] [0, 1, 2, 3]
        wxyx 4 [3, 0, 1, 0] [0, 1, 2, 3]
        wxyy 4 [3, 0, 1, 1] [0, 1, 2, 3]
        wxyz 4 [3, 0, 1, 2] [0, 1, 2, 3]
        wxyw 4 [3, 0, 1, 3] [0, 1, 2, 3]
        wxzx 4 [3, 0, 2, 0] [0, 1, 2, 3]
        wxzy 4 [3, 0, 2, 1] [0, 1, 2, 3]
        wxzz 4 [3, 0, 2, 2] [0, 1, 2, 3]
        wxzw 4 [3, 0, 2, 3] [0, 1, 2, 3]
        wxwx 4 [3, 0, 3, 0] [0, 1, 2, 3]
        wxwy 4 [3, 0, 3, 1] [0, 1, 2, 3]
        wxwz 4 [3, 0, 3, 2] [0, 1, 2, 3]
        wxww 4 [3, 0, 3, 3] [0, 1, 2, 3]
        wyxx 4 [3, 1, 0, 0] [0, 1, 2, 3]
        wyxy 4 [3, 1, 0, 1] [0, 1, 2, 3]
        wyxz 4 [3, 1, 0, 2] [0, 1, 2, 3]
        wyxw 4 [3, 1, 0, 3] [0, 1, 2, 3]
        wyyx 4 [3, 1, 1, 0] [0, 1, 2, 3]
        wyyy 4 [3, 1, 1, 1] [0, 1, 2, 3]
        wyyz 4 [3, 1, 1, 2] [0, 1, 2, 3]
        wyyw 4 [3, 1, 1, 3] [0, 1, 2, 3]
        wyzx 4 [3, 1, 2, 0] [0, 1, 2, 3]
        wyzy 4 [3, 1, 2, 1] [0, 1, 2, 3]
        wyzz 4 [3, 1, 2, 2] [0, 1, 2, 3]
        wyzw 4 [3, 1, 2, 3] [0, 1, 2, 3]
        wywx 4 [3, 1, 3, 0] [0, 1, 2, 3]
        wywy 4 [3, 1, 3, 1] [0, 1, 2, 3]
        wywz 4 [3, 1, 3, 2] [0, 1, 2, 3]
        wyww 4 [3, 1, 3, 3] [0, 1, 2, 3]
        wzxx 4 [3, 2, 0, 0] [0, 1, 2, 3]
        wzxy 4 [3, 2, 0, 1] [0, 1, 2, 3]
        wzxz 4 [3, 2, 0, 2] [0, 1, 2, 3]
        wzxw 4 [3, 2, 0, 3] [0, 1, 2, 3]
        wzyx 4 [3, 2, 1, 0] [0, 1, 2, 3]
        wzyy 4 [3, 2, 1, 1] [0, 1, 2, 3]
        wzyz 4 [3, 2, 1, 2] [0, 1, 2, 3]
        wzyw 4 [3, 2, 1, 3] [0, 1, 2, 3]
        wzzx 4 [3, 2, 2, 0] [0, 1, 2, 3]
        wzzy 4 [3, 2, 2, 1] [0, 1, 2, 3]
        wzzz 4 [3, 2, 2, 2] [0, 1, 2, 3]
        wzzw 4 [3, 2, 2, 3] [0, 1, 2, 3]
        wzwx 4 [3, 2, 3, 0] [0, 1, 2, 3]
        wzwy 4 [3, 2, 3, 1] [0, 1, 2, 3]
        wzwz 4 [3, 2, 3, 2] [0, 1, 2, 3]
        wzww 4 [3, 2, 3, 3] [0, 1, 2, 3]
        wwxx 4 [3, 3, 0, 0] [0, 1, 2, 3]
        wwxy 4 [3, 3, 0, 1] [0, 1, 2, 3]
        wwxz 4 [3, 3, 0, 2] [0, 1, 2, 3]
        wwxw 4 [3, 3, 0, 3] [0, 1, 2, 3]
        wwyx 4 [3, 3, 1, 0] [0, 1, 2, 3]
        wwyy 4 [3, 3, 1, 1] [0, 1, 2, 3]
        wwyz 4 [3, 3, 1, 2] [0, 1, 2, 3]
        wwyw 4 [3, 3, 1, 3] [0, 1, 2, 3]
        wwzx 4 [3, 3, 2, 0] [0, 1, 2, 3]
        wwzy 4 [3, 3, 2, 1] [0, 1, 2, 3]
        wwzz 4 [3, 3, 2, 2] [0, 1, 2, 3]
        wwzw 4 [3, 3, 2, 3] [0, 1, 2, 3]
        wwwx 4 [3, 3, 3, 0] [0, 1, 2, 3]
        wwwy 4 [3, 3, 3, 1] [0, 1, 2, 3]
        wwwz 4 [3, 3, 3, 2] [0, 1, 2, 3]
        wwww 4 [3, 3, 3, 3] [0, 1, 2, 3]
    }};
}

/// Swizzling data for the given dimension.
/// See the [`build.rs`] file to learn more.
#[macro_export]
macro_rules! with_swizzling_for_dim_unique {
    (1, $f: ident $(,$($args:tt)*)?) => { $f! { $([$($args)*])? 1
        x 1 [0] [0]
    }};
    (2, $f: ident $(,$($args:tt)*)?) => { $f! { $([$($args)*])? 2
        y 1 [1] [0]
        xy 2 [0, 1] [0, 1]
        yx 2 [1, 0] [0, 1]
    }};
    (3, $f: ident $(,$($args:tt)*)?) => { $f! { $([$($args)*])? 3
        z 1 [2] [0]
        xz 2 [0, 2] [0, 1]
        yz 2 [1, 2] [0, 1]
        zx 2 [2, 0] [0, 1]
        zy 2 [2, 1] [0, 1]
        xyz 3 [0, 1, 2] [0, 1, 2]
        xzy 3 [0, 2, 1] [0, 1, 2]
        yxz 3 [1, 0, 2] [0, 1, 2]
        yzx 3 [1, 2, 0] [0, 1, 2]
        zxy 3 [2, 0, 1] [0, 1, 2]
        zyx 3 [2, 1, 0] [0, 1, 2]
    }};
    (4, $f: ident $(,$($args:tt)*)?) => { $f! { $([$($args)*])? 4
        w 1 [3] [0]
        xw 2 [0, 3] [0, 1]
        yw 2 [1, 3] [0, 1]
        zw 2 [2, 3] [0, 1]
        wx 2 [3, 0] [0, 1]
        wy 2 [3, 1] [0, 1]
        wz 2 [3, 2] [0, 1]
        xyw 3 [0, 1, 3] [0, 1, 2]
        xzw 3 [0, 2, 3] [0, 1, 2]
        xwy 3 [0, 3, 1] [0, 1, 2]
        xwz 3 [0, 3, 2] [0, 1, 2]
        yxw 3 [1, 0, 3] [0, 1, 2]
        yzw 3 [1, 2, 3] [0, 1, 2]
        ywx 3 [1, 3, 0] [0, 1, 2]
        ywz 3 [1, 3, 2] [0, 1, 2]
        zxw 3 [2, 0, 3] [0, 1, 2]
        zyw 3 [2, 1, 3] [0, 1, 2]
        zwx 3 [2, 3, 0] [0, 1, 2]
        zwy 3 [2, 3, 1] [0, 1, 2]
        wxy 3 [3, 0, 1] [0, 1, 2]
        wxz 3 [3, 0, 2] [0, 1, 2]
        wyx 3 [3, 1, 0] [0, 1, 2]
        wyz 3 [3, 1, 2] [0, 1, 2]
        wzx 3 [3, 2, 0] [0, 1, 2]
        wzy 3 [3, 2, 1] [0, 1, 2]
        xyzw 4 [0, 1, 2, 3] [0, 1, 2, 3]
        xywz 4 [0, 1, 3, 2] [0, 1, 2, 3]
        xzyw 4 [0, 2, 1, 3] [0, 1, 2, 3]
        xzwy 4 [0, 2, 3, 1] [0, 1, 2, 3]
        xwyz 4 [0, 3, 1, 2] [0, 1, 2, 3]
        xwzy 4 [0, 3, 2, 1] [0, 1, 2, 3]
        yxzw 4 [1, 0, 2, 3] [0, 1, 2, 3]
        yxwz 4 [1, 0, 3, 2] [0, 1, 2, 3]
        yzxw 4 [1, 2, 0, 3] [0, 1, 2, 3]
        yzwx 4 [1, 2, 3, 0] [0, 1, 2, 3]
        ywxz 4 [1, 3, 0, 2] [0, 1, 2, 3]
        ywzx 4 [1, 3, 2, 0] [0, 1, 2, 3]
        zxyw 4 [2, 0, 1, 3] [0, 1, 2, 3]
        zxwy 4 [2, 0, 3, 1] [0, 1, 2, 3]
        zyxw 4 [2, 1, 0, 3] [0, 1, 2, 3]
        zywx 4 [2, 1, 3, 0] [0, 1, 2, 3]
        zwxy 4 [2, 3, 0, 1] [0, 1, 2, 3]
        zwyx 4 [2, 3, 1, 0] [0, 1, 2, 3]
        wxyz 4 [3, 0, 1, 2] [0, 1, 2, 3]
        wxzy 4 [3, 0, 2, 1] [0, 1, 2, 3]
        wyxz 4 [3, 1, 0, 2] [0, 1, 2, 3]
        wyzx 4 [3, 1, 2, 0] [0, 1, 2, 3]
        wzxy 4 [3, 2, 0, 1] [0, 1, 2, 3]
        wzyx 4 [3, 2, 1, 0] [0, 1, 2, 3]
    }};
}
