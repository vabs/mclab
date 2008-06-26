package matlab;

%%

//general header info
%public
%final
%class LengthScanner

%unicode
%function getEOFPosition
%type TextPosition

//track line and column
%line
%column

%%

. | \n {}
<<EOF>> { return new TextPosition(yyline + 1, yycolumn + 1); }